package org.mimirdb.rowids

import org.apache.spark.sql.{ SparkSession, DataFrame, Column }
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.AliasIdentifier
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.JoinType

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

object AnnotateWithSequenceNumber
{
  val ATTRIBUTE = "__MIMIR_ROW_INDEX"
  val FIELD_TYPE = StructField(ATTRIBUTE, LongType)
  val DEFAULT_FIRST_ROW = 0

  def withSequenceNumber(df: DataFrame)(op: DataFrame => DataFrame): DataFrame =
  {
    val annotated = apply(df)
    val afterOp = op(annotated)
    afterOp.select(
      df.schema.fieldNames.map { afterOp(_) }:_*
    )
  }

  def strip(df: DataFrame): DataFrame =
    df.select(
        df.schema.fieldNames
          .filter { !_.equalsIgnoreCase(ATTRIBUTE) }
          .map { df(_) }:_*
      )

  def apply(
    df: DataFrame,
    attribute: String = ATTRIBUTE,
    offset:Long = 0
  ): DataFrame = {
    if(df.schema.fieldNames.contains(ATTRIBUTE)){
      return df
    }

    val annotatedPlan =
      apply(
        df.queryExecution.analyzed,
        df.queryExecution.sparkSession,
        AttributeReference(attribute, LongType, false)(),
        offset
      )
    new DataFrame(
      df.queryExecution.sparkSession,
      annotatedPlan,
      RowEncoder(StructType(df.schema.fields :+ FIELD_TYPE))
    )
  }

  def apply(
    plan: LogicalPlan,
    session: SparkSession,
    attribute: Attribute,
    offset:Long
  ): LogicalPlan =
  {
    val PARTITION_ID     = AttributeReference(attribute.name+"_PARTITION_ID", LongType, true)()
    val PARTITION_OFFSET = AttributeReference(attribute.name+"_PARTITION_OFFSET", LongType, false)()
    val INTERNAL_ID      = AttributeReference(attribute.name+"_INTERNAL_ID", LongType, false)()
    val COUNT_ATTR       = AttributeReference(attribute.name+"_COUNT", LongType, false)()

    def ResolvedAlias(expr: Expression, attr: Attribute): NamedExpression =
      Alias(expr, attr.name)(attr.exprId)

    val planWithPartitionedIdentifierAttributes =
      Project(
        plan.output ++ Seq(
          ResolvedAlias(SparkPartitionID(), PARTITION_ID),
          ResolvedAlias(MonotonicallyIncreasingID(), INTERNAL_ID)
        ),
        plan
      )

    /**
     * id offset for input rows for a given session (Seq of Integers)
     *
     * For each partition, determine the difference between the identifier
     * assigned to elements of the partition, and the true ROWID. This
     * offset value is computed as:
     *   [true id of the first element of the partition]
     *     - [first assigned id of the partition]
     *
     * The true ID is computed by a windowed aggregate over the counts
     * of all partitions with earlier identifiers. The window includes
     * the count of the current partition, so that gets subtracted off.
     *
     * The first assigned ID is simply obtained by the FIRST aggregate.
     *   [[ Oliver: Might MIN be safer? ]]
     *
     * Calling this function pre-computes and caches the resulting
     * partition-id -> offset map.  Because the partition-ids are
     * sequentially assigned, starting from zero, we can represent the
     * Map more efficiently as a Sequence.
     *
     * The map might change every session, so the return value of this
     * function should not be cached between sessions.
     */
    val planToComputeFirstPerPartitionIdentifier =
      Project(Seq(
        PARTITION_ID,
        ResolvedAlias(
          Add(
            Subtract(
              Subtract(
                WindowExpression(
                  AggregateExpression(
                    Sum(COUNT_ATTR),
                    Complete,false),
                  WindowSpecDefinition(
                    Seq(),
                    Seq(SortOrder(PARTITION_ID, Ascending)),
                    UnspecifiedFrame)
                ),
                COUNT_ATTR),
              INTERNAL_ID
            ),
            Literal(offset)
          ),COUNT_ATTR)
        ),
        Sort(
          Seq(SortOrder(PARTITION_ID, Ascending)),
          true,
          Aggregate(
            Seq(PARTITION_ID),
            Seq(
              PARTITION_ID,
              ResolvedAlias(AggregateExpression(
                  Count(Seq(Literal(1))),Complete,false
                ), COUNT_ATTR),
              ResolvedAlias(AggregateExpression(
                  First(INTERNAL_ID,false),Complete,false
                ),INTERNAL_ID)
            ),
          planWithPartitionedIdentifierAttributes)
        )
      )

    val firstPerPartitionIdentifierMap =
      new DataFrame(
        session,
        planToComputeFirstPerPartitionIdentifier,
        RowEncoder(StructType(Seq(
          StructField(PARTITION_ID.name, PARTITION_ID.dataType),
          StructField(COUNT_ATTR.name, COUNT_ATTR.dataType),
        )))
      ).cache()
       .collect()
       .map { row => row.getLong(0) -> row.getLong(1) }
       .toMap

    def lookupFirstIdentifier(partition: Expression) =
      ScalaUDF(
        function = (partitionId:Int) => firstPerPartitionIdentifierMap(partitionId),
        dataType = LongType,
        children = Seq(partition),
        inputEncoders = Seq(Some(ExpressionEncoder[Int])),
//        Seq(false),
//        inputEncoders = Seq(IntegerType),
        udfName = Some("FIRST_IDENTIFIER_FOR_PARTITION"), /*name hint*/
        nullable = true, /*nullable*/
        udfDeterministic = true /*deterministic*/
      )

    Project(
      plan.output :+ ResolvedAlias(
        (If(
          IsNull(PARTITION_ID),
          INTERNAL_ID,
          Add(
            INTERNAL_ID,
            lookupFirstIdentifier(new Column(PARTITION_ID).expr)
          )
        )), attribute
      ),
      planWithPartitionedIdentifierAttributes
    )

  }
}
