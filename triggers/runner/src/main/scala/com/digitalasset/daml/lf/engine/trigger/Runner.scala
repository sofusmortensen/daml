// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.engine.trigger

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import com.google.rpc.status.Status
import com.typesafe.scalalogging.StrictLogging

import io.grpc.StatusRuntimeException
import java.time.Instant
import java.util.UUID

import scalaz.syntax.tag._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import com.digitalasset.api.util.TimeProvider
import com.digitalasset.daml.lf.{CompiledPackages, PureCompiledPackages}
import com.digitalasset.daml.lf.archive.Dar
import com.digitalasset.daml.lf.data.ImmArray
import com.digitalasset.daml.lf.data.Ref._
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.daml.lf.language.Ast._
import com.digitalasset.daml.lf.speedy.Compiler
import com.digitalasset.daml.lf.speedy.Pretty
import com.digitalasset.daml.lf.speedy.{SExpr, SValue, Speedy}
import com.digitalasset.daml.lf.speedy.SExpr._
import com.digitalasset.daml.lf.speedy.SResult._
import com.digitalasset.daml.lf.speedy.SValue._
import com.digitalasset.ledger.api.refinements.ApiTypes.ApplicationId
import com.digitalasset.ledger.api.v1.commands.Commands
import com.digitalasset.ledger.api.v1.completion.Completion
import com.digitalasset.ledger.api.v1.command_submission_service.SubmitRequest
import com.digitalasset.ledger.api.v1.event._
import com.digitalasset.ledger.api.v1.ledger_offset.LedgerOffset
import com.digitalasset.ledger.api.v1.transaction.Transaction
import com.digitalasset.ledger.api.v1.transaction_filter.{
  Filters,
  InclusiveFilters,
  TransactionFilter
}
import com.digitalasset.ledger.client.LedgerClient
import com.digitalasset.ledger.client.services.commands.CompletionStreamElement._
import com.digitalasset.platform.participant.util.LfEngineToApi.toApiIdentifier
import com.digitalasset.platform.services.time.TimeProviderType

sealed trait TriggerMsg
final case class CompletionMsg(c: Completion) extends TriggerMsg
final case class TransactionMsg(t: Transaction) extends TriggerMsg
final case class HeartbeatMsg() extends TriggerMsg

final case class Trigger(expr: Expr, ty: TypeConApp, triggerIds: TriggerIds)

class Runner(
    client: LedgerClient,
    timeProviderType: TimeProviderType,
    applicationId: ApplicationId,
    val compiledPackages: CompiledPackages,
    trigger: Trigger,
    party: String,
) extends StrictLogging {
  private val compiler = Compiler(compiledPackages.packages)
  private val converter = Converter(compiledPackages, trigger.triggerIds)
  // This is a map from the command ids used on the ledger API to the command ids used internally
  // in the trigger which are just incremented at each step.
  private var commandIdMap: Map[UUID, String] = Map.empty
  // This is the set of command ids emitted by the trigger.
  // We track this to detect collisions.
  private var usedCommandIds: Set[String] = Set.empty

  // Handles the result of initialState or update, i.e., (s, [Commands], Text)
  // by submitting the commands, printing the log message and returning
  // the new state
  private def handleStepResult(
      converter: Converter,
      v: SValue,
      submit: SubmitRequest => Unit): SValue =
    v match {
      case SRecord(recordId, _, values)
          if recordId.qualifiedName ==
            QualifiedName(
              DottedName.assertFromString("DA.Types"),
              DottedName.assertFromString("Tuple2")) => {
        val newState = values.get(0)
        val commandVal = values.get(1)
        logger.debug(s"New state: $newState")
        commandVal match {
          case SList(transactions) =>
            // Each transaction is a list of commands
            for (commands <- transactions) {
              converter.toCommands(commands) match {
                case Left(err) => throw new ConverterException(err)
                case Right((commandId, commands)) => {
                  if (usedCommandIds.contains(commandId)) {
                    throw new RuntimeException(s"Duplicate command id: $commandId")
                  }
                  usedCommandIds += commandId
                  val commandUUID = UUID.randomUUID
                  commandIdMap += (commandUUID -> commandId)
                  val commandsArg = Commands(
                    ledgerId = client.ledgerId.unwrap,
                    applicationId = applicationId.unwrap,
                    commandId = commandUUID.toString,
                    party = party,
                    commands = commands
                  )
                  submit(SubmitRequest(commands = Some(commandsArg)))
                }
              }
            }
          case _ => {}
        }
        newState
      }
      case v => {
        throw new RuntimeException(s"Expected Tuple2 but got $v")
      }
    }

  // Run speedy until we arrive at a value.
  private def stepToValue(machine: Speedy.Machine): Unit = {
    while (!machine.isFinal) {
      machine.step() match {
        case SResultContinue => ()
        case SResultError(err) => {
          logger.error(Pretty.prettyError(err, machine.ptx).render(80))
          throw err
        }
        case res => {
          val errMsg = s"Unexpected speedy result: $res"
          logger.error(errMsg)
          throw new RuntimeException(errMsg)
        }
      }
    }
  }

  // Return the heartbeat specified by the user.
  def getTriggerHeartbeat(): Option[FiniteDuration] = {
    val heartbeat = compiler.compile(
      ERecProj(trigger.ty, Name.assertFromString("heartbeat"), trigger.expr)
    )
    var machine = Speedy.Machine.fromSExpr(heartbeat, false, compiledPackages)
    stepToValue(machine)
    machine.toSValue match {
      case SOptional(None) => None
      case SOptional(Some(relTime)) =>
        converter.toFiniteDuration(relTime) match {
          case Left(err) => throw new ConverterException(err)
          case Right(duration) => Some(duration)
        }
      case value => throw new ConverterException(s"Expected Optional but got $value.")
    }
  }

  // Return the trigger filter specified by the user.
  def getTriggerFilter(): TransactionFilter = {
    val registeredTemplates = compiler.compile(
      ERecProj(trigger.ty, Name.assertFromString("registeredTemplates"), trigger.expr))
    var machine =
      Speedy.Machine.fromSExpr(registeredTemplates, false, compiledPackages)
    stepToValue(machine)
    val templateIds = machine.toSValue match {
      case SVariant(_, "AllInDar", _) => {
        val packages: Seq[(PackageId, Package)] = compiledPackages.packageIds
          .map(pkgId => (pkgId, compiledPackages.getPackage(pkgId).get))
          .toSeq
        packages.flatMap({
          case (pkgId, pkg) =>
            pkg.modules.toList.flatMap({
              case (modName, module) =>
                module.definitions.toList.flatMap({
                  case (entityName, definition) =>
                    definition match {
                      case DDataType(_, _, DataRecord(_, Some(tpl))) =>
                        Seq(toApiIdentifier(Identifier(pkgId, QualifiedName(modName, entityName))))
                      case _ => Seq()
                    }
                })
            })
        })
      }
      case SVariant(_, "RegisteredTemplates", v) =>
        converter.toRegisteredTemplates(v) match {
          case Right(tpls) => tpls.map(toApiIdentifier(_))
          case Left(err) => throw new ConverterException(err)
        }
      case v => throw new ConverterException(s"Expected AllInDar or RegisteredTemplates but got $v")
    }
    TransactionFilter(List((party, Filters(Some(InclusiveFilters(templateIds))))).toMap)
  }

  private def msgSource(
      client: LedgerClient,
      offset: LedgerOffset,
      heartbeat: Option[FiniteDuration],
      party: String,
      filter: TransactionFilter)(implicit materializer: Materializer)
    : (Source[TriggerMsg, NotUsed], (String, StatusRuntimeException) => Unit) = {
    // We use the queue to post failures that occur directly on command submission as opposed to
    // appearing asynchronously on the completion stream
    val (completionQueue, completionQueueSource) =
      Source.queue[Completion](10, OverflowStrategy.backpressure).preMaterialize()
    val transactionSource =
      client.transactionClient
        .getTransactions(offset, None, filter, verbose = true)
        .map[TriggerMsg](TransactionMsg)
    val completionSource =
      client.commandClient
        .completionSource(List(party), offset)
        .mapConcat({
          case CheckpointElement(_) => List()
          case CompletionElement(c) => List(c)
        })
        .merge(completionQueueSource)
        .map[TriggerMsg](CompletionMsg)
    val source = heartbeat match {
      case Some(interval) =>
        transactionSource
          .merge(completionSource)
          .merge(Source.tick[TriggerMsg](interval, interval, HeartbeatMsg()))
      case None => transactionSource.merge(completionSource)
    }
    def postSubmitFailure(commandId: String, s: StatusRuntimeException) = {
      val _ = completionQueue.offer(
        Completion(
          commandId,
          Some(Status(s.getStatus().getCode().value(), s.getStatus().getDescription()))))
    }
    (source, postSubmitFailure)
  }

  private def getTriggerSink(
      acs: Seq[CreatedEvent],
      submit: SubmitRequest => Unit,
  ): Sink[TriggerMsg, Future[SExpr]] = {
    logger.info(s"Trigger is running as ${party}")
    val update =
      compiler.compile(ERecProj(trigger.ty, Name.assertFromString("update"), trigger.expr))
    val getInitialState =
      compiler.compile(ERecProj(trigger.ty, Name.assertFromString("initialState"), trigger.expr))

    var machine = Speedy.Machine.fromSExpr(null, false, compiledPackages)
    val createdExpr: SExpr = SEValue(converter.fromACS(acs) match {
      case Left(err) => throw new ConverterException(err)
      case Right(x) => x
    })
    val clientTime: Timestamp =
      Timestamp.assertFromInstant(Runner.getTimeProvider(timeProviderType).getCurrentTime)
    val initialState =
      SEApp(
        getInitialState,
        Array(
          SEValue(SParty(Party.assertFromString(party))),
          SEValue(STimestamp(clientTime)): SExpr,
          createdExpr))
    machine.ctrl = Speedy.CtrlExpr(initialState)
    stepToValue(machine)
    val evaluatedInitialState = handleStepResult(converter, machine.toSValue, submit)
    logger.debug(s"Initial state: $evaluatedInitialState")
    Flow[TriggerMsg]
      .mapConcat[TriggerMsg]({
        case CompletionMsg(c) =>
          try {
            commandIdMap.get(UUID.fromString(c.commandId)) match {
              case None => List()
              case Some(internalCommandId) =>
                List(CompletionMsg(c.copy(commandId = internalCommandId)))
            }
          } catch {
            // This happens for invalid UUIDs which we might get for completions not emitted by the trigger.
            case e: IllegalArgumentException => List()
          }
        case TransactionMsg(t) =>
          try {
            commandIdMap.get(UUID.fromString(t.commandId)) match {
              case None => List(TransactionMsg(t.copy(commandId = "")))
              case Some(internalCommandId) =>
                List(TransactionMsg(t.copy(commandId = internalCommandId)))
            }
          } catch {
            // This happens for invalid UUIDs which we might get for transactions not emitted by the trigger.
            case e: IllegalArgumentException => List(TransactionMsg(t.copy(commandId = "")))
          }
        case x @ HeartbeatMsg() => List(x)
      })
      .toMat(Sink.fold[SExpr, TriggerMsg](SEValue(evaluatedInitialState))((state, message) => {
        val messageVal = message match {
          case TransactionMsg(transaction) => {
            converter.fromTransaction(transaction) match {
              case Left(err) => throw new ConverterException(err)
              case Right(x) => x
            }
          }
          case CompletionMsg(completion) => {
            val status = completion.getStatus
            if (status.code != 0) {
              logger.warn(s"Command failed: ${status.message}, code: ${status.code}")
            }
            converter.fromCompletion(completion) match {
              case Left(err) => throw new ConverterException(err)
              case Right(x) => x
            }
          }
          case HeartbeatMsg() => converter.fromHeartbeat
        }
        val clientTime: Timestamp =
          Timestamp.assertFromInstant(Runner.getTimeProvider(timeProviderType).getCurrentTime)
        machine.ctrl = Speedy.CtrlExpr(
          SEApp(update, Array(SEValue(STimestamp(clientTime)): SExpr, SEValue(messageVal), state)))
        stepToValue(machine)
        val newState = handleStepResult(converter, machine.toSValue, submit)
        SEValue(newState)
      }))(Keep.right[NotUsed, Future[SExpr]])
  }

  // Query the ACS. This allows you to separate the initialization of the initial state
  // from the first run. This is only intended for tests.
  def queryACS(filter: TransactionFilter)(
      implicit materializer: Materializer,
      executionContext: ExecutionContext): Future[(Seq[CreatedEvent], LedgerOffset)] = {
    for {
      acsResponses <- client.activeContractSetClient
        .getActiveContracts(filter, verbose = true)
        .runWith(Sink.seq)
      offset = Array(acsResponses: _*).lastOption
        .fold(LedgerOffset().withBoundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN))(resp =>
          LedgerOffset().withAbsolute(resp.offset))
    } yield (acsResponses.flatMap(x => x.activeContracts), offset)
  }

  // Run the trigger given the state of the ACS.
  def runWithACS[T](
      heartbeat: Option[FiniteDuration],
      acs: Seq[CreatedEvent],
      offset: LedgerOffset,
      filter: TransactionFilter,
      msgFlow: Graph[FlowShape[TriggerMsg, TriggerMsg], T] = Flow[TriggerMsg],
  )(implicit materializer: Materializer, executionContext: ExecutionContext): (T, Future[SExpr]) = {
    val (source, postFailure) = msgSource(client, offset, heartbeat, party, filter)
    def submit(req: SubmitRequest) = {
      val f = client.commandClient
        .withTimeProvider(Some(Runner.getTimeProvider(timeProviderType)))
        .submitSingleCommand(req)
      f.failed.foreach({
        case s: StatusRuntimeException =>
          postFailure(req.getCommands.commandId, s)
        case e => logger.error(s"Unexpected exception: $e")
      })
    }
    source
      .viaMat(msgFlow)(Keep.right[NotUsed, T])
      .toMat(getTriggerSink(acs, submit))(Keep.both)
      .run()
  }
}

object Runner extends StrictLogging {
  // Return the time provider for a given time provider type.
  def getTimeProvider(ty: TimeProviderType): TimeProvider = {
    ty match {
      case TimeProviderType.Static => TimeProvider.Constant(Instant.EPOCH)
      case TimeProviderType.WallClock => TimeProvider.UTC
      case _ => throw new RuntimeException(s"Unexpected TimeProviderType: $ty")
    }
  }

  // Given an identifier, search for the trigger and return the necessary metadata
  // to run it.
  // Throws an exception if the trigger is not found or not a valid trigger.
  def getTrigger(compiledPackages: CompiledPackages, triggerId: Identifier): Trigger = {
    val (tyCon: TypeConName, stateTy) =
      compiledPackages
        .getPackage(triggerId.packageId)
        .flatMap(_.lookupIdentifier(triggerId.qualifiedName).toOption) match {
        case Some(DValue(TApp(TTyCon(tcon), stateTy), _, _, _)) => (tcon, stateTy)
        case _ => {
          val errMsg = s"Identifier ${triggerId.qualifiedName} does not point to a trigger"
          throw new RuntimeException(errMsg)
        }
      }
    val triggerIds = TriggerIds(tyCon.packageId)
    if (tyCon == triggerIds.damlTriggerLowLevel("Trigger")) {
      logger.debug("Running low-level trigger")
      val triggerVal = EVal(triggerId)
      val triggerTy = TypeConApp(tyCon, ImmArray(stateTy))
      Trigger(triggerVal, triggerTy, triggerIds)
    } else if (tyCon == triggerIds.damlTrigger("Trigger")) {
      logger.debug("Running high-level trigger")
      val runTrigger = EVal(triggerIds.damlTrigger("runTrigger"))
      val triggerState = TTyCon(triggerIds.damlTriggerInternal("TriggerState"))
      val lowLevelTriggerTy = triggerIds.damlTriggerLowLevel("Trigger")
      val lowTriggerVal = EApp(runTrigger, EVal(triggerId))
      val lowStateTy = TApp(triggerState, stateTy)
      val lowTriggerTy = TypeConApp(lowLevelTriggerTy, ImmArray(lowStateTy))
      Trigger(lowTriggerVal, lowTriggerTy, triggerIds)
    } else {
      val errMsg =
        s"Identifier ${triggerId.qualifiedName} does not point to a trigger. Its type must be Daml.Trigger.Trigger or Daml.Trigger.LowLevel.Trigger."
      throw new RuntimeException(errMsg)
    }
  }

  // Construct a trigger runner from the given DAR.
  def fromDar(
      dar: Dar[(PackageId, Package)],
      triggerId: Identifier,
      client: LedgerClient,
      timeProviderType: TimeProviderType,
      applicationId: ApplicationId,
      party: String): Runner = {
    val darMap = dar.all.toMap
    val compiler = Compiler(darMap)
    val compiledPackages =
      PureCompiledPackages(darMap, compiler.compilePackages(darMap.keys)).right.get
    val trigger = Runner.getTrigger(compiledPackages, triggerId)
    new Runner(
      client,
      timeProviderType,
      applicationId,
      compiledPackages,
      trigger,
      party
    )
  }

  // Convience wrapper that creates the runner and runs the trigger.
  def run(
      dar: Dar[(PackageId, Package)],
      triggerId: Identifier,
      client: LedgerClient,
      timeProviderType: TimeProviderType,
      applicationId: ApplicationId,
      party: String
  )(implicit materializer: Materializer, executionContext: ExecutionContext): Future[SExpr] = {
    val runner = Runner.fromDar(dar, triggerId, client, timeProviderType, applicationId, party)
    val filter = runner.getTriggerFilter()
    val heartbeat = runner.getTriggerHeartbeat()
    for {
      (acs, offset) <- runner.queryACS(filter)
      finalState <- runner
        .runWithACS(heartbeat, acs, offset, filter)
        ._2
    } yield finalState
  }
}
