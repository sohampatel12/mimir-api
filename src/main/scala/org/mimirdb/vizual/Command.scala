package org.mimirdb.vizual

import play.api.libs.json._
import org.apache.spark.sql.Column
import org.apache.spark.sql.functions._
import org.mimirdb.rowids.AnnotateWithRowIds
import org.apache.spark.sql.types.DataType
import org.mimirdb.spark.Schema.dataTypeFormat

sealed trait Command
object Command
{
  implicit val format = Format[Command](
    new Reads[Command]{
      def reads(j: JsValue): JsResult[Command] =
      {
        j.as[Map[String, JsValue]].get("id") match {
          case None => JsError("No 'id' field")
          case Some(JsString(id)) => id.toLowerCase match {
            case "deletecolumn"  => JsSuccess(j.as[DeleteColumn])
            case "deleterow"     => JsSuccess(j.as[DeleteRow])
            case "insertcolumn"  => JsSuccess(j.as[InsertColumn])
            case "insertrow"     => JsSuccess(j.as[InsertRow])
            case "movecolumn"    => JsSuccess(j.as[MoveColumn])
            case "moverow"       => JsSuccess(j.as[MoveRow])
            case "projection"    => JsSuccess(j.as[FilterColumns])
            case "renamecolumn"  => JsSuccess(j.as[RenameColumn])
            case "updatecell"    => JsSuccess(j.as[UpdateCell])
            case "sort"          => JsSuccess(j.as[Sort])
            case _ => JsError("Not a valid Vizier command")
          }
          case Some(_) => JsError("Expecting the 'id' field to be a string")
        }
      }
    },
    new Writes[Command] {
      def writes(c: Command): JsValue = 
      {
        val (cmd, js) = 
          c match {
            case x:DeleteColumn  =>  ("deletecolumn",   Json.toJson(x))
            case x:DeleteRow     =>  ("deleterow",      Json.toJson(x))
            case x:InsertColumn  =>  ("insertcolumn",   Json.toJson(x))
            case x:InsertRow     =>  ("insertrow",      Json.toJson(x))
            case x:MoveColumn    =>  ("movecolumn",     Json.toJson(x))
            case x:MoveRow       =>  ("moverow",        Json.toJson(x))
            case x:FilterColumns =>  ("projection",     Json.toJson(x))
            case x:RenameColumn  =>  ("renamecolumn",   Json.toJson(x))
            case x:UpdateCell    =>  ("updatecell",     Json.toJson(x))
            case x:Sort          =>  ("sort",           Json.toJson(x))
          }
        Json.toJson(
          js.as[Map[String, JsValue]] 
            ++ Map("id" -> JsString(cmd))
        )
      }
    }
  )

}

case class DeleteColumn(
  column: Int
) extends Command
object DeleteColumn
{ implicit val format: Format[DeleteColumn] = Json.format }

//////////////////////////

case class DeleteRow(
  row: Long
) extends Command
object DeleteRow
{ implicit val format: Format[DeleteRow] = Json.format }

//////////////////////////

case class InsertColumn(
  position: Option[Int],
  name: String,
  dataType: Option[DataType]
) extends Command
object InsertColumn
{ implicit val format: Format[InsertColumn] = Json.format }

//////////////////////////

case class InsertRow(
  position: Option[Long],
  values: Option[Seq[JsValue]]
) extends Command
object InsertRow
{ implicit val format: Format[InsertRow] = Json.format }

//////////////////////////

case class MoveColumn(
  column: Int,
  position: Int
) extends Command
object MoveColumn
{ implicit val format: Format[MoveColumn] = Json.format }

//////////////////////////

case class MoveRow(
  row: String,
  position: Long
) extends Command
object MoveRow
{ implicit val format: Format[MoveRow] = Json.format }

//////////////////////////

case class FilteredColumn(
  columns_column: Int,
  columns_name: String
) 
{
  def column = columns_column
  def name = columns_name
}
object FilteredColumn
{ implicit val format: Format[FilteredColumn] = Json.format }

//////////////////////////

case class FilterColumns(
  columns: Seq[FilteredColumn],
) extends Command
object FilterColumns
{ implicit val format: Format[FilterColumns] = Json.format }

//////////////////////////

case class RenameColumn(
  column: Int,
  name: String
) extends Command
object RenameColumn
{ implicit val format: Format[RenameColumn] = Json.format }

//////////////////////////

case class SortColumn(
  column: Int,
  order: String // "ASC", "DESC"
)
object SortColumn
{ implicit val format: Format[SortColumn] = Json.format }

case class Sort(
  columns: Seq[SortColumn]
) extends Command
object Sort
{ implicit val format: Format[Sort] = Json.format }

//////////////////////////

sealed trait RowSelection
{
  def isAllRows: Boolean = false
  def predicate: Column
  def apply(ifTrue: Column)(ifFalse: Column): Column = 
    when(predicate, ifTrue).otherwise(ifFalse)
}

object RowSelection
{
  implicit val format = Format[RowSelection](
    new Reads[RowSelection] {
      def reads(j: JsValue): JsResult[RowSelection] =
        j match { 
          case x: JsNumber => JsSuccess(RowsById(Set(x.as[Long].toString)))
          case x: JsString => {
            if(x.value.startsWith("=")){
              JsSuccess(RowsByConstraint(x.value.substring(1)))
            } else {
              JsSuccess(RowsById(Set(x.as[String])))
            }
          }
          case x: JsArray => JsSuccess(RowsById(x.as[Seq[String]].toSet))
          case JsNull => JsSuccess(AllRows())
          case _ => JsError("Not a valid row selection")
        }
    },
    new Writes[RowSelection] {
      def writes(j: RowSelection): JsValue =
        j match { 
          case RowsById(rows) => Json.toJson(rows.toSeq)
          case AllRows() => JsNull
          case RowsByConstraint(constraint) => Json.toJson(s"=$constraint")
        }
    }
  )

}

case class RowsById(rows: Set[String]) extends RowSelection
{
  def predicate = 
    if(rows.isEmpty) { lit(false) }
    else if(rows.size == 1) { col(AnnotateWithRowIds.ATTRIBUTE) === rows.head }
    else { col(AnnotateWithRowIds.ATTRIBUTE).isin(rows.toSeq:_*)}
}
case class AllRows() extends RowSelection
{
  def predicate = lit(true)
  override def apply(ifTrue: Column)(ifFalse: Column): Column = ifTrue
  override def isAllRows = true
}
case class RowsByConstraint(constraint: String) extends RowSelection
{
  lazy val predicate = expr(constraint)
}

case class UpdateCell(
  column: Int,
  row: Option[RowSelection],
  value: Option[JsValue],
  comment: Option[String]
) extends Command
{
  def getRows = row.getOrElse { AllRows() }
}
object UpdateCell
{ implicit val format: Format[UpdateCell] = Json.format }
