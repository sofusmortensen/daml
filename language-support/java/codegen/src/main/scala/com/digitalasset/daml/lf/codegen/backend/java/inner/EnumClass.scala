package com.digitalasset.daml.lf.codegen.backend.java.inner

import com.daml.ledger.javaapi
import com.digitalasset.daml.lf.data.Ref.Identifier
import com.digitalasset.daml.lf.iface
import com.squareup.javapoet._
import com.typesafe.scalalogging.StrictLogging
import javax.lang.model.element.Modifier

private[inner] object EnumClass extends StrictLogging {

  def generate(
      className: ClassName,
      identifier: Identifier,
      enum: iface.Enum,
  ): TypeSpec = {

    logger.info("Start")
    val enumType = TypeSpec.enumBuilder(className).addModifiers(Modifier.PUBLIC)
    enum.constructors.foreach(enumType.addEnumConstant)
    enumType.addField(generateValuesArray(enum))
    enumType.addMethod(generateFromValue(className, enum))
    enumType.addMethod(generateToValue(className))
    logger.debug("End")
    enumType.build()
  }

  private def generateValuesArray(enum: iface.Enum): FieldSpec =
    FieldSpec
      .builder(ArrayTypeName.of(classOf[javaapi.data.Enum]), "values")
      .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
      .initializer(
        CodeBlock.of(
          enum.constructors
            .map(c => s"""new Enum("$c")""")
            .mkString("{\n    ", ",\n    ", "\n  }")))
      .build()

  private def generateFromValue(
      className: ClassName,
      enum: iface.Enum
  ): MethodSpec = {
    logger.debug(s"Generating fromValue static method for $enum")

    MethodSpec
      .methodBuilder("fromValue")
      .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
      .returns(className)
      .addParameter(classOf[javaapi.data.Value], "value$")
      .addStatement(
        "$T enum$$ = value$$.asEnum().orElseThrow(() -> new IllegalArgumentException($S))",
        classOf[javaapi.data.Enum],
        s"Expected Enum to build an instance of the Enum ${className.simpleName()}"
      )
      .addStatement("return $T.valueOf(enum$$.getConstructor())", className)
      .build()
  }

  private def generateToValue(className: ClassName): MethodSpec =
    MethodSpec
      .methodBuilder("toValue")
      .addModifiers(Modifier.PUBLIC)
      .returns(classOf[javaapi.data.Enum])
      .addStatement("return $T.values[ordinal()]", className)
      .build()

}
