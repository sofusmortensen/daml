// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao.events

import java.sql.Connection
import java.time.Instant

import scala.util.Try

private[events] trait PostCommitValidationData {

  def lookupContractKey(submitter: Party, key: Key)(
      implicit connection: Connection): Option[ContractId]

  def lookupMaximumLedgerTime(ids: Set[ContractId])(
      implicit connection: Connection): Try[Option[Instant]]

}
