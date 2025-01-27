package org.mimirdb.api

import org.apache.spark.sql.{ SparkSession, DataFrame, Column }
import org.mimirdb.data.{ Catalog, LoadConstructor }
import org.mimirdb.lenses.Lenses
import org.mimirdb.lenses.implementation.TestCaseGeocoder
import org.apache.spark.serializer.KryoSerializer
import org.apache.sedona.viz.core.Serde.SedonaVizKryoRegistrator
import org.apache.sedona.viz.sql.utils.SedonaVizRegistrator
import org.mimirdb.data.JDBCMetadataBackend
import org.mimirdb.caveats.PrettyPrint


object SharedSparkTestInstance
{
  lazy val spark = 
    SparkSession.builder
      .appName("Mimir-Caveat-Test")
      .master("local[*]")
      .config("spark.serializer", classOf[KryoSerializer].getName)
      .config("spark.kryo.registrator", classOf[SedonaVizKryoRegistrator].getName)
      .getOrCreate()
  lazy val df = /* R(A int, B int, C int) */
    spark.range(0, 5)

  def loadCSV(name: String, url: String, header: Boolean = true, typeInference: Boolean = false)
  {
    var constructor = LoadConstructor(
                        url = url, 
                        format = "csv", 
                        sparkOptions = Map("header" -> header.toString)
                      )
    if(typeInference){
      constructor = constructor.withLens(
        MimirAPI.sparkSession, 
        Lenses.typeInference, 
        "in "+name
      )
    }
    MimirAPI.catalog.put(name, constructor, Set())
  }

  def loadText(name: String, url: String)
  {
    var constructor = LoadConstructor(
                        url = url, 
                        format = "text",
                        sparkOptions = Map()
                      )
    MimirAPI.catalog.put(name, constructor, Set())
  }

  def initAPI {
    this.synchronized {
      if(MimirAPI.sparkSession == null){
        PrettyPrint.simpleOutput = true
        MimirAPI.sparkSession = spark
      }
      if(MimirAPI.conf == null){
        MimirAPI.conf = new MimirConfig(Seq())
        MimirAPI.conf.verify()
      }
      if(MimirAPI.catalog == null){
        MimirAPI.initCatalog("sqlite:target/test.db")

        // And load up some example test data
        loadCSV("TEST_R", "test_data/r.csv")
        loadCSV("GEO", "test_data/geo.csv")
        loadCSV("SEQ", "test_data/seq.csv")

        // Finally, initialize geocoding with the test harness
        InitSpark.initPlugins(spark)
        Lenses.initGeocoding(
          Seq(TestCaseGeocoder),
          MimirAPI.catalog
        )
      }
    }
  }
}

trait SharedSparkTestInstance
{
  lazy val spark = SharedSparkTestInstance.spark
  lazy val df = SharedSparkTestInstance.df

  def dataset(name:String) = MimirAPI.catalog.get(name)
}