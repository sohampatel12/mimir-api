package org.mimirdb.spark

import java.io.ByteArrayInputStream
import java.util.Base64
import scala.sys.process._
import com.typesafe.scalalogging.LazyLogging

import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.expressions.{ Expression, PythonUDF }
import org.apache.spark.PythonUDFWorkaround // defined in mimir-api

/**
 * Utility class for converting Vizier-exported python functions for use in
 * spark.  
 *
 * @param   pythonPath      The path to the python executable
 * 
 * Basic workflow:
 * ```
 * val pythonUDF = PythonUDFBuilder()
 * val pickle = pythonUDF.pickle(... code ...)
 * val udf = pythonUDF(pickle)
 * 
 * val result: Expression = udf(Seq(expr1, expr2, ...)): 
 * ```
 */
class PythonUDFBuilder(val pythonPath: String)
  extends LazyLogging
{

  def apply(vizierFunctionScript: String): (Seq[Expression] => PythonUDF) = 
    apply(pickle(vizierFunctionScript))

  def apply(vizierFunctionScript: String, name: String): (Seq[Expression] => PythonUDF) = 
    apply(pickle(vizierFunctionScript), name = Some(name))

  def apply(
    pickled: Array[Byte], 
    name: Option[String] = None,
    dataType: Option[DataType] = None
  ): (Seq[Expression] => PythonUDF) = 
  {
    val actualName = name.getOrElse { python(GET_NAME(pickled)).replaceAll("\n", "") }
    lazy val actualDataType = dataType.getOrElse { 
      DataType.fromJson(python(GET_DT(pickled)).replaceAll("\n", "")) 
    }
    return (args: Seq[Expression]) => PythonUDFWorkaround(
      command = pickled,
      envVars = new java.util.HashMap(),
      pythonIncludes = new java.util.ArrayList(),
      pythonExec = pythonPath,
      pythonVer = version
    )(
      name = actualName,
      dataType = actualDataType,
      children = args,
      evalType = 100, // SQL_BATCHED_UDF
      true
    )
  }

  def pickle(vizierFunctionScript: String): Array[Byte] = 
  {
    logger.debug(s"Pickling: \n$vizierFunctionScript")
    Base64.getDecoder().decode(
      python(GENERATE_PICKLE(vizierFunctionScript))
        .replaceAll("\n", "")
    )
  }

  def runPickle(pickled: Array[Byte], args: String = ""): String = 
    python(RUN_PICKLE(pickled, args))

  def python(script: String): String = 
  {
    logger.debug(s"Running:\n$script")
    Process(pythonPath) .#< {  new ByteArrayInputStream(script.getBytes()) }  .!!
  }

  lazy val version = 
    Process(Seq(pythonPath, "--version")).!!
      .replaceAll("\n", "")
      .split(" ").reverse.head
      .split("\\.").take(2).mkString(".")


  def GENERATE_PICKLE(vizier_fn: String, t: DataType = StringType) = s"""
from pyspark import cloudpickle
import sys
import base64
from pyspark.sql.types import DataType, NullType, StringType, BinaryType, BooleanType, DateType, TimestampType, DecimalType, DoubleType, FloatType, ByteType, IntegerType, LongType, ShortType, ArrayType, MapType, StructField, StructType
import pyspark.sql.types as pyspark_types

class VizierUDFExtractor:
  def __init__(self):
    self.fn = None
  def export_module_decorator(self, fn):
    self.fn = fn
    return fn
vizierdb = VizierUDFExtractor()
def return_type(data_type):
  def wrap(fn):
    fn.__return_type__ = data_type
    return fn
  return wrap

@vizierdb.export_module_decorator
${vizier_fn}

if not hasattr(vizierdb.fn, "__return_type__") or vizierdb.fn.__return_type__ is None:
  vizierdb.fn.__return_type__ = StringType()

assert(vizierdb.fn is not None)
pickled_fn = cloudpickle.dumps((vizierdb.fn, vizierdb.fn.__return_type__))
encoded_fn = base64.encodebytes(pickled_fn)
print(encoded_fn.decode())
"""

  def GET_NAME(pickled: Array[Byte]) = s"""
from pyspark import cloudpickle
import base64

encoded_fn = "${new String(Base64.getEncoder().encode(pickled)).replaceAll("\n", "")}"
pickled_fn = base64.decodebytes(encoded_fn.encode())
fn = cloudpickle.loads(pickled_fn)[0]
print(fn.__name__)
"""

  def GET_DT(pickled: Array[Byte]) = s"""
from pyspark import cloudpickle
import base64

encoded_fn = "${new String(Base64.getEncoder().encode(pickled)).replaceAll("\n", "")}"
pickled_fn = base64.decodebytes(encoded_fn.encode())
fn = cloudpickle.loads(pickled_fn)[0]
if(hasattr(fn, "__return_type__")):
  print(fn.__return_type__.json())
else:
  print('"string"')
"""

  def RUN_PICKLE(pickled: Array[Byte], args: String) = s"""
from pyspark import cloudpickle
import base64

encoded_fn = "${new String(Base64.getEncoder().encode(pickled)).replaceAll("\n", "")}"
pickled_fn = base64.decodebytes(encoded_fn.encode())
fn = cloudpickle.loads(pickled_fn)[0]
print(fn(${args}))
"""

}

object PythonUDFBuilder
{
  def apply(pythonPath: Option[String] = None): PythonUDFBuilder = 
    return new PythonUDFBuilder(
      pythonPath.getOrElse { defaultPython() }
    )

  def defaultPython(): String =
    Process(Seq("which", "python3"))
      .lineStream
      .headOption
      .getOrElse { throw new RuntimeException("Can't find python3") }
}