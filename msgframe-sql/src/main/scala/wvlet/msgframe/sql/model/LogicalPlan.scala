/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.msgframe.sql.model

import java.util.Locale

import wvlet.msgframe.sql.model.LogicalPlan.Expression

trait LogicalPlan {
  def modelName = this.getClass.getSimpleName
  def children: Seq[LogicalPlan]
}

trait SQLSig {
  def sig: String
}

/**
  *
  */
object LogicalPlan {

  trait LeafNode extends LogicalPlan {
    override def children: Seq[LogicalPlan] = Seq.empty
  }

  trait UnaryNode extends LogicalPlan {
    def child: LogicalPlan
    override def children = child :: Nil
  }

  trait BinaryNode extends LogicalPlan {
    def left: LogicalPlan
    def right: LogicalPlan
    override def children = Seq(left, right)
  }

  sealed trait Expression extends LogicalPlan

  case class ParenthesizedExpression(expr: Expression) extends Expression with UnaryNode {
    override def child: LogicalPlan = expr
  }

  // Qualified name (QName), such as table and column names
  case class QName(parts: Seq[String]) extends Expression with LeafNode {
    override def toString = parts.mkString(".")
  }
  sealed trait Identifier extends Expression with LeafNode
  case class DigitId(value: Int) extends Identifier {
    override def toString: String = value.toString
  }
  case class UnquotedIdentifier(value: String) extends Identifier {
    override def toString: String = value
  }
  case class BackQuotedIdentifier(value: String) extends Identifier {
    override def toString = s"`${value}`"
  }
  case class QuotedIdentifier(value: String) extends Identifier {
    override def toString = s""""${value}""""
  }

  object QName {
    def apply(s: String): QName = {
      // TODO handle quotation
      QName(s.split("\\.").toSeq)
    }
  }

  // Operator for ign relations
  sealed trait Relation extends LogicalPlan with SQLSig

  sealed trait UnaryRelation extends Relation {
    def inputRelation: Relation
  }

  case class ParenthesizedRelation(child: Relation) extends UnaryRelation with UnaryNode {
    override def inputRelation = child
    override def sig: String   = child.sig
  }
  case class AliasedRelation(child: Relation, alias: String, columnNames: Option[Seq[String]])
      extends UnaryRelation
      with UnaryNode {
    override def inputRelation = child
    override def sig: String   = child.sig
  }

  case class Values(rows: Seq[Expression]) extends Relation {
    override def children: Seq[LogicalPlan] = rows
    override def sig: String = {
      s"V[${rows.length}]"
    }
  }
  case class Table(name: QName) extends Relation with LeafNode {
    override def sig: String = "T"
  }
  case class RawSQL(sql: String) extends Relation with LeafNode {
    override def sig: String = "Q"
  }
  case class Sort(in: Relation, orderBy: Seq[SortItem]) extends UnaryRelation with UnaryNode {
    override def inputRelation      = in
    override def child: LogicalPlan = in
    override def sig: String        = s"O[${orderBy.length}](${in.sig})"
  }

  case class Limit(in: Relation, limit: Int) extends UnaryRelation with UnaryNode {
    override def inputRelation      = in
    override def child: LogicalPlan = in
    override def sig: String        = s"L(${in.sig})"
  }

  case class Filter(in: Relation, filterExpr: Expression) extends UnaryRelation {
    override def inputRelation              = in
    override def children: Seq[LogicalPlan] = Seq(in)
    override def sig: String                = s"F(${in.sig})"
  }

  case object EmptyRelation extends Relation with LeafNode {
    override def sig = ""
  }

  private def isSelectAll(selectItems: Seq[SelectItem]): Boolean = {
    selectItems.exists {
      case AllColumns(x) => true
      case _             => false
    }
  }

  case class Project(in: Relation, isDistinct: Boolean, selectItems: Seq[SelectItem]) extends UnaryRelation {
    override def inputRelation              = in
    override def children: Seq[LogicalPlan] = Seq(in)
    override def sig: String = {
      val prefix = if (isDistinct) "distinct " else ""
      val proj   = if (isSelectAll(selectItems)) "*" else s"${selectItems.length}"
      s"P[${prefix}${proj}](${in.sig})"
    }
  }

  case class Aggregate(in: Relation,
                       selectItems: Seq[SelectItem],
                       groupingKeys: Seq[Expression],
                       having: Option[Expression])
      extends UnaryRelation {

    override def inputRelation = in

    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b ++= selectItems
      b += in
      b ++= groupingKeys
      having.map(b += _)
      b.result()
    }

    override def sig: String = {
      val proj = if (isSelectAll(selectItems)) "*" else s"${selectItems.length}"
      s"A[${proj},${groupingKeys.length}](${in.sig})"
    }

    override def toString =
      s"Aggregate[${groupingKeys.mkString(",")}](Select[${selectItems.mkString(", ")}(${in})"
  }

  case class Query(withQuery: With, body: Relation) extends UnaryRelation {
    override def inputRelation = body

    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b ++= withQuery.children
      b += body
      b.result()
    }

    override def sig: String = {
      val wq = for (q <- withQuery.queries) yield {
        s"${q.query.sig}"
      }
      val wq_s = wq.mkString(",")

      s"W[${wq_s}](${body.sig})"
    }
  }
  case class With(recursive: Boolean, queries: Seq[WithQuery]) extends LogicalPlan {
    override def children: Seq[LogicalPlan] = queries.flatMap(_.children)
  }
  case class WithQuery(name: Identifier, query: Relation, columnNames: Option[Seq[Identifier]])
      extends LogicalPlan
      with UnaryNode {
    override def child: LogicalPlan = query
  }

  // Joins
  case class Join(joinType: JoinType, left: Relation, right: Relation, cond: JoinCriteria) extends Relation {
    override def children: Seq[LogicalPlan] = Seq(left, right, cond)
    override def sig: String = {
      s"${joinType.symbol}(${left.sig},${right.sig})"
    }
  }
  sealed abstract class JoinType(val symbol: String)
  // Exact match (= equi join)
  case object InnerJoin extends JoinType("J")
  // Joins for preserving left table entries
  case object LeftOuterJoin extends JoinType("LJ")
  // Joins for preserving right table entries
  case object RightOuterJoin extends JoinType("RJ")
  // Joins for preserving both table entries
  case object FullOuterJoin extends JoinType("FJ")
  // Cartesian product of two tables
  case object CrossJoin extends JoinType("CJ")
  // From clause contains only table names, and
  // Where clause specifies join criteria
  case object ImplicitJoin extends JoinType("J")

  sealed trait JoinCriteria extends Expression
  case object NaturalJoin   extends JoinCriteria with LeafNode
  case class JoinUsing(columns: Seq[String]) extends JoinCriteria with LeafNode {
    override def toString: String = s"JoinUsing(${columns.mkString(",")})"
  }
  case class JoinOn(expr: Expression) extends JoinCriteria with UnaryNode {
    override def child: LogicalPlan = expr
  }

  sealed trait SelectItem extends Expression
  case class AllColumns(prefix: Option[QName]) extends SelectItem with LeafNode {
    override def toString = s"${prefix.map(x => s"${x}.*").getOrElse("*")}"
  }
  case class SingleColumn(expr: Expression, alias: Option[Expression]) extends SelectItem {
    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b += expr
      alias.map(b += _)
      b.result()
    }
    override def toString = alias.map(a => s"${expr} as ${a}").getOrElse(s"${expr}")
  }

  case class SortItem(sortKey: Expression, ordering: Option[SortOrdering] = None, nullOrdering: Option[NullOrdering])
      extends Expression
      with UnaryNode {
    override def child: LogicalPlan = sortKey
  }

  sealed trait SetOperation extends Relation
  case class Intersect(relations: Seq[Relation], isDistinct: Boolean) extends SetOperation {
    override def children: Seq[LogicalPlan] = relations
    override def sig = {
      val prefix = if (isDistinct) "IX[distinct]" else "IX"
      s"${prefix}(${relations.map(_.sig).mkString(",")})"
    }
  }
  case class Except(left: Relation, right: Relation, isDistinct: Boolean) extends SetOperation with BinaryNode {
    override def sig = {
      val prefix = if (isDistinct) "EX[distinct]" else "EX"
      s"${prefix}(${left.sig},${right.sig})"
    }
  }
  case class Union(relations: Seq[Relation], isDistinct: Boolean) extends SetOperation {
    override def children: Seq[LogicalPlan] = relations
    override def toString = {
      val name = if (isDistinct) "Union" else "UnionAll"
      s"${name}(${relations.mkString(",")})"
    }
    override def sig = {
      val prefix = if (isDistinct) "U[distinct]" else "U"
      val in     = relations.map(_.sig).mkString(",")
      s"${prefix}(${in})"
    }
  }

  // Sort ordering
  sealed trait SortOrdering
  case object Ascending extends SortOrdering {
    override def toString = "ASC"
  }
  case object Descending extends SortOrdering {
    override def toString = "DESC"
  }

  sealed trait NullOrdering
  case object NullIsFirst extends NullOrdering {
    override def toString = "NULLS FIRST"
  }
  case object NullIsLast extends NullOrdering {
    override def toString = "NULLS LAST"
  }

  case object UndefinedOrder extends NullOrdering

  // Window functions
  case class Window(partitionBy: Seq[Expression], orderBy: Seq[SortItem], frame: Option[WindowFrame])
      extends LogicalPlan {
    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b ++= partitionBy
      b ++= orderBy
      frame.map(b += _)
      b.result()
    }
  }

  sealed trait FrameType
  case object RangeFrame extends FrameType {
    override def toString = "RANGE"
  }
  case object RowsFrame extends FrameType {
    override def toString = "ROWS"
  }

  sealed trait FrameBound
  case object UnboundedPreceding extends FrameBound {
    override def toString: String = "UNBOUNDED PRECEDING"
  }
  case object UnboundedFollowing extends FrameBound {
    override def toString: String = "UNBOUNDED FOLLOWING"
  }
  case class Preceding(n: Long) extends FrameBound {
    override def toString: String = s"${n} PRECEDING"
  }

  case class Following(n: Long) extends FrameBound {
    override def toString: String = s"${n} FOLLOWING"
  }
  case object CurrentRow extends FrameBound {
    override def toString: String = "CURRENT ROW"
  }

  case class WindowFrame(frameType: FrameType, start: FrameBound, end: Option[FrameBound])
      extends LogicalPlan
      with LeafNode {
    override def toString: String = {
      val s = Seq.newBuilder[String]
      s += frameType.toString
      if (end.isDefined) {
        s += "BETWEEN"
      }
      s += start.toString
      if (end.isDefined) {
        s += "AND"
        s += end.get.toString
      }
      s.result().mkString(" ")
    }
  }

  // Function
  case class FunctionCall(name: QName,
                          args: Seq[Expression],
                          isDistinct: Boolean,
                          filter: Option[Expression],
                          window: Option[Window])
      extends Expression {
    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b ++= args
      filter.map(b += _)
      window.map(b ++= _.children)
      b.result()
    }

    def functionName: String = name.toString.toLowerCase(Locale.US)
    override def toString    = s"FunctionCall(${name}, ${args.mkString(", ")}, distinct:${isDistinct}, window:${window})"
  }
  case class LambdaExpr(body: Expression, args: Seq[String]) extends Expression with UnaryNode {
    override def child: LogicalPlan = body
  }

  class Ref(name: QName) extends Expression with LeafNode

  // Conditional expression
  sealed trait ConditionalExpression                              extends Expression
  case object NoOp                                                extends ConditionalExpression with LeafNode
  case class Eq(left: Expression, right: Expression)              extends ConditionalExpression with BinaryNode
  case class NotEq(left: Expression, right: Expression)           extends ConditionalExpression with BinaryNode
  case class And(left: Expression, right: Expression)             extends ConditionalExpression with BinaryNode
  case class Or(left: Expression, right: Expression)              extends ConditionalExpression with BinaryNode
  case class Not(child: Expression)                               extends ConditionalExpression with UnaryNode
  case class LessThan(left: Expression, right: Expression)        extends ConditionalExpression with BinaryNode
  case class LessThanOrEq(left: Expression, right: Expression)    extends ConditionalExpression with BinaryNode
  case class GreaterThan(left: Expression, right: Expression)     extends ConditionalExpression with BinaryNode
  case class GreaterThanOrEq(left: Expression, right: Expression) extends ConditionalExpression with BinaryNode
  case class Between(e: Expression, a: Expression, b: Expression) extends ConditionalExpression {
    override def children: Seq[LogicalPlan] = Seq(e, a, b)
  }
  case class IsNull(child: Expression)    extends ConditionalExpression with UnaryNode
  case class IsNotNull(child: Expression) extends ConditionalExpression with UnaryNode
  case class In(a: Expression, list: Seq[Expression]) extends ConditionalExpression {
    override def children: Seq[LogicalPlan] = Seq(a) ++ list
  }
  case class NotIn(a: Expression, list: Seq[Expression]) extends ConditionalExpression {
    override def children: Seq[LogicalPlan] = Seq(a) ++ list
  }
  case class InSubQuery(a: Expression, in: Relation) extends ConditionalExpression {
    override def children: Seq[LogicalPlan] = Seq(a, in)
  }
  case class NotInSubQuery(a: Expression, in: Relation) extends ConditionalExpression {
    override def children: Seq[LogicalPlan] = Seq(a, in)
  }
  case class Like(left: Expression, right: Expression)            extends ConditionalExpression with BinaryNode
  case class NotLike(left: Expression, right: Expression)         extends ConditionalExpression with BinaryNode
  case class DistinctFrom(left: Expression, right: Expression)    extends ConditionalExpression with BinaryNode
  case class NotDistinctFrom(left: Expression, right: Expression) extends ConditionalExpression with BinaryNode

  case class IfExpr(cond: ConditionalExpression, onTrue: Expression, onFalse: Expression) extends Expression {
    override def children: Seq[LogicalPlan] = Seq(cond, onTrue, onFalse)
  }
  case class CaseExpr(operand: Option[Expression], whenClauses: Seq[WhenClause], defaultValue: Option[Expression])
      extends Expression {
    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      operand.map(b += _)
      b ++= whenClauses
      defaultValue.map(b += _)
      b.result()
    }
  }
  case class WhenClause(condition: Expression, result: Expression) extends Expression {
    override def children: Seq[LogicalPlan] = Seq(condition, result)
  }

  case class Exists(child: Expression) extends Expression with UnaryNode

  // Arithmetic expr
  abstract sealed class BinaryExprType(val symbol: String)
  case object Add      extends BinaryExprType("+")
  case object Subtract extends BinaryExprType("-")
  case object Multiply extends BinaryExprType("*")
  case object Divide   extends BinaryExprType("/")
  case object Modulus  extends BinaryExprType("%")

  sealed trait ArithmeticExpression extends Expression
  case class ArithmeticBinaryExpr(exprType: BinaryExprType, left: Expression, right: Expression)
      extends ArithmeticExpression
      with BinaryNode
  case class ArithmeticUnaryExpr(sign: Sign, child: Expression) extends ArithmeticExpression with UnaryNode

  abstract sealed class Sign(val symbol: String)
  case object Positive extends Sign("+")
  case object Negative extends Sign("-")

  // Set quantifier
  sealed trait SetQuantifier extends Expression with LeafNode {
    def isDistinct: Boolean
  }
  case object All extends SetQuantifier {
    override def isDistinct: Boolean = false
  }
  case object Distinct extends SetQuantifier {
    override def isDistinct: Boolean = true
  }

  // Literal
  sealed trait Literal extends Expression with LeafNode
  case object NullLiteral extends Literal {
    override def toString: String = "NULL"
  }
  sealed trait BooleanLiteral extends Literal
  case object TrueLiteral extends BooleanLiteral {
    override def toString: String = "TRUE"
  }
  case object FalseLiteral extends BooleanLiteral {
    override def toString: String = "FALSE"
  }
  case class StringLiteral(value: String) extends Literal {
    override def toString = s"'${value}'"
  }
  case class TimeLiteral(value: String) extends Literal {
    override def toString = s"TIME '${value}'"
  }
  case class TimestampLiteral(value: String) extends Literal {
    override def toString = s"TIMESTAMP '${value}'"
  }
  case class DecimalLiteral(value: String) extends Literal {
    override def toString = s"DECIMAL '${value}'"
  }
  case class CharLiteral(value: String) extends Literal {
    override def toString = s"CHAR '${value}'"
  }
  case class DoubleLiteral(value: Double) extends Literal {
    override def toString = value.toString
  }
  case class LongLiteral(value: Long) extends Literal {
    override def toString = value.toString
  }
  case class IntervalLiteral(value: String, sign: Sign, startField: IntervalField, end: Option[IntervalField])
      extends Literal {
    override def toString: String = {
      s"INTERVAL ${sign.symbol} '${value}' ${startField}"
    }
  }

  case class GenericLiteral(tpe: String, value: String) extends Literal {
    override def toString = s"${tpe} '${value}'"
  }
  case class BinaryLiteral(binary: String) extends Literal

  sealed trait IntervalField extends LogicalPlan with LeafNode
  case object Year           extends IntervalField
  case object Month          extends IntervalField
  case object Day            extends IntervalField
  case object Hour           extends IntervalField
  case object Minute         extends IntervalField
  case object Second         extends IntervalField

  // Value constructor
  case class ArrayConstructor(values: Seq[Expression]) extends Expression {
    override def children: Seq[LogicalPlan] = values
  }
  abstract sealed class CurrentTimeBase(name: String, precision: Option[Int]) extends Expression with LeafNode
  case class CurrentTime(precision: Option[Int])                              extends CurrentTimeBase("current_time", precision)
  case class CurrentDate(precision: Option[Int])                              extends CurrentTimeBase("current_date", precision)
  case class CurrentTimestamp(precision: Option[Int])                         extends CurrentTimeBase("current_timestamp", precision)
  case class CurrentLocalTime(precision: Option[Int])                         extends CurrentTimeBase("localtime", precision)
  case class CurrentLocalTimeStamp(precision: Option[Int])                    extends CurrentTimeBase("localtimestamp", precision)

  // 1-origin parameter
  case class Parameter(index: Int) extends Expression with LeafNode
  case class SubQueryExpression(query: Relation) extends Expression with UnaryNode {
    override def child: LogicalPlan = query
  }

  case class Cast(expr: Expression, tpe: String, tryCast: Boolean = false) extends Expression with UnaryNode {
    override def child: LogicalPlan = expr
  }

  // DDL
  sealed trait DDL extends LogicalPlan with SQLSig
  case class CreateSchema(schema: QName, ifNotExists: Boolean, properties: Option[Seq[SchemaProperty]]) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(schema) ++ properties.getOrElse(Seq.empty)
    override def sig                        = "CS"
  }
  case class SchemaProperty(key: Identifier, value: Expression) extends Expression {
    override def children: Seq[LogicalPlan] = Seq(key, value)
  }
  case class DropSchema(schema: QName, ifExists: Boolean, cascade: Boolean) extends DDL with UnaryNode {
    override def child: LogicalPlan = schema
    override def sig                = "DS"
  }

  case class RenameSchema(schema: QName, renameTo: Identifier) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(schema, renameTo)
    override def sig                        = "RS"
  }
  case class CreateTable(table: QName, ifNotExists: Boolean, tableElems: Seq[TableElement]) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table) ++ tableElems
    override def sig                        = "CT"
  }

  case class CreateTableAs(table: QName,
                           ifNotEotExists: Boolean,
                           columnAliases: Option[Seq[Identifier]],
                           query: Relation)
      extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table) ++ columnAliases.getOrElse(Seq.empty) ++ Seq(query)
    override def sig                        = s"CT(${query.sig})"
  }
  case class DropTable(table: QName, ifExists: Boolean) extends DDL with UnaryNode {
    override def child: LogicalPlan = table
    override def sig                = "DT"
  }
  trait Update extends LogicalPlan with SQLSig

  case class InsertInto(table: QName, columnAliases: Option[Seq[Identifier]], query: Relation) extends Update {
    override def children: Seq[LogicalPlan] = Seq(table) ++ columnAliases.getOrElse(Seq.empty) ++ Seq(query)
    override def sig                        = s"I(${query.sig})"
  }
  case class Delete(table: QName, where: Option[Expression]) extends Update {
    override def children: Seq[LogicalPlan] = {
      val b = Seq.newBuilder[LogicalPlan]
      b += table
      where.map(b += _)
      b.result()
    }
    override def sig = "D"
  }
  case class RenameTable(table: QName, renameTo: QName) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table, renameTo)
    override def sig                        = "RT"
  }
  case class RenameColumn(table: QName, column: Identifier, renameTo: Identifier) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table, column, renameTo)
    override def sig                        = "RC"
  }
  case class DropColumn(table: QName, column: Identifier) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table, column)
    override def sig                        = "DC"
  }
  case class AddColumn(table: QName, column: ColumnDef) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(table, column)
    override def sig                        = "AC"
  }

  sealed trait TableElement extends Expression
  case class ColumnDef(columnName: Identifier, tpe: ColumnType) extends TableElement {
    override def children: Seq[LogicalPlan] = Seq(columnName, tpe)
  }
  case class ColumnType(tpe: String) extends Expression with LeafNode

  case class ColumnDefLike(tableName: QName, includeProperties: Boolean) extends UnaryNode with TableElement {
    override def child = tableName
  }
  case class CreateView(viewName: QName, replace: Boolean, query: Relation) extends DDL {
    override def children: Seq[LogicalPlan] = Seq(viewName, query)
    override def sig                        = "CV"
  }
  case class DropView(viewName: QName, ifExists: Boolean) extends DDL with UnaryNode {
    override def child = viewName
    override def sig   = "DV"
  }
}

object SQLFunction {

  //case class AggregateExpression(func: AggregateFunction, mode: AggregateMode, isDistinct: Boolean) extends Expression

  sealed trait AggregateMode
  case object PartialAggregate extends AggregateMode
  case object FinalAggregate   extends AggregateMode
  case object FullAggregate    extends AggregateMode

  sealed trait AggregateFunction
  case class Sum(e: Expression)                      extends AggregateFunction
  case class Avg(e: Expression)                      extends AggregateFunction
  case class Count(e: Expression)                    extends AggregateFunction
  case class CountDistinct(e: Expression)            extends AggregateFunction
  case class CountMinSketch(e: Expression)           extends AggregateFunction
  case class CountApproximateDistinct(e: Expression) extends AggregateFunction
  case class ApproximatePercentile(e: Expression)    extends AggregateFunction
  case class Min(e: Expression)                      extends AggregateFunction
  case class Max(e: Expression)                      extends AggregateFunction
  case class First(e: Expression)                    extends AggregateFunction
  case class Last(e: Expression)                     extends AggregateFunction

}