package org.mimirdb.api.request

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import play.api.libs.json._
import org.apache.spark.sql.types._

import org.mimirdb.api.MimirAPI
import org.mimirdb.api.SharedSparkTestInstance

class LoadSpec 
  extends Specification
  with SharedSparkTestInstance
  with BeforeAll
{
  import spark.implicits._

  def beforeAll = SharedSparkTestInstance.initAPI

  "load local files" >> {
    val request = LoadRequest(
                    file              = "test_data/r.csv",
                    format            = "csv",
                    inferTypes        = false,
                    detectHeaders     = true,
                    humanReadableName = Some("A TEST OF THE THING"),
                    backendOption     = Seq(),
                    dependencies      = Some(Seq()),
                    resultName        = None,
                    properties        = None
                  )
    val response = request.handle.as[LoadResponse]

    MimirAPI.catalog.get(response.name)
                    .count() must be equalTo(7)

    MimirAPI.catalog.flush(response.name)

    MimirAPI.catalog.get(response.name)
                    .count() must be equalTo(7)

  }

  "load remote files" >> {
    val request = LoadRequest(
                    file              = "https://odin.cse.buffalo.edu/public_data/r.csv",
                    format            = "csv",
                    inferTypes        = false,
                    detectHeaders     = true,
                    humanReadableName = Some("ANOTHER TEST OF THE THING"),
                    backendOption     = Seq(),
                    dependencies      = Some(Seq()),
                    resultName        = None,
                    properties        = None
                  )
    val response = request.handle.as[LoadResponse]

    MimirAPI.catalog.get(response.name)
                    .count() must be equalTo(7)

    MimirAPI.catalog.flush(response.name)

    MimirAPI.catalog.get(response.name)
                    .count() must be equalTo(7)
  }

  "load files with type inference" >> {

    val request = LoadRequest(
                    file              = "test_data/r.csv",
                    format            = "csv",
                    inferTypes        = true, 
                    detectHeaders     = true,
                    humanReadableName = Some("STILL MORE THING TESTS"),
                    backendOption     = Seq(),
                    dependencies      = Some(Seq()),
                    resultName        = None,
                    properties        = None
                  )
    val response = request.handle.as[LoadResponse]

    val allRows = 
      MimirAPI.catalog.get(response.name).collect()
    val dataRow = 
      allRows(0)
    dataRow.schema.fieldNames.toSet must contain("A")
    dataRow.fieldIndex("A") must be greaterThanOrEqualTo(0)
    val firstCell = dataRow.getAs[AnyRef]("A")
    firstCell must beAnInstanceOf[java.lang.Short]
  }

  "load inlined datasets" >> {

    val request = LoadInlineRequest(
                    schema = Seq(
                      StructField("num", IntegerType),
                      StructField("str", StringType)
                    ),
                    data = Seq(
                      Seq[JsValue](JsNumber(1), JsString("A")),
                      Seq[JsValue](JsNumber(2), JsString("B")),
                      Seq[JsValue](JsNumber(3), JsString("C"))
                    ),
                    dependencies = None,
                    resultName = Some("INLINED_LOAD_TEST"),
                    properties = None,
                    humanReadableName = None
                  )

    val response = Json.toJson(request).as[LoadInlineRequest].handle.as[LoadResponse]
    response.name must beEqualTo("INLINED_LOAD_TEST")

    val allRows = 
      MimirAPI.catalog.get(response.name).collect()
    val dataRow =
      allRows(0)
    dataRow.schema.fieldNames.toSet must contain(eachOf("num", "str"))
    dataRow.getAs[AnyRef]("num") must beEqualTo(1)
    dataRow.getAs[AnyRef]("str") must beEqualTo("A")
  }

}