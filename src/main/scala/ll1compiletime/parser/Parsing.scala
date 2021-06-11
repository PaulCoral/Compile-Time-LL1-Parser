package ll1compiletime.parser

import ll1compiletime.syntax._

import ParsingTable.ParsingTableContext
import ParsingTable.ParsingTableInstruction
import ParsingTable.ParsingTableContext._
import ParsingTable.ParsingTableInstruction._
import ParsingTable.{Nullable,Leaf,Node}

import scala.quoted._
import scala.collection.mutable.{Set,Map}
import scala.annotation.tailrec

class Parsing[Kind] {

    private val ready: Set[Int] = Set()
    private val rec: Set[Int] = Set()
    private val idToProperties: Map[Int, Properties] = Map()
    private val childToParent: Map[Int, Set[Int]] = Map()

    private val conflicts: Set[LL1Conflict] = Set()

    private val table: Map[(Int,Kind),ParsingTableInstruction] = Map()
    private val nullable: Map[Int,Nullable] = Map()

    def apply(sd: SyntaxDefinition[?,?,Kind]):PartialParsingTable[Kind] = {        
        val s = sd.entryPoint
        cleaning
        setUp(s.asInstanceOf[Syntax[Any,?,Kind]])
        propagate
        if(conflicts.nonEmpty){
            throw Exception(conflicts.toString)
        }
        PartialParsingTable[Kind](s.id,table.toMap, nullable.toMap)
    }

    private def cleaning = {
        ready.clear
        idToProperties.clear
        childToParent.clear
        conflicts.clear
        table.clear
    }

    private def addChildToParent(child:Int, parent: Int) = {
        childToParent.get(child) match {
            case None => childToParent.put(child, Set(parent))
            case Some(set) => set.add(parent)
        }
    }

    private def setUp(s: Syntax[Any,?,Kind]):Unit = 
        val prop = Properties(s)
        idToProperties.put(s.id, prop)
        s match {
            case Success(v) => 
                prop.nullable = Some(Leaf(s.id))
                prop.update(true)
                ready.add(s.id)
            
            case Failure() =>
                prop.isProductive = false
                prop.hasConflict = true
                ready.add(s.id)
                prop.update(true)

            case Elem(k:Kind) => 
                prop.first.add(k)
                ready.add(s.id)
                prop.update(true)

            case Transform(inner,f) => 
                addChildToParent(inner.id,s.id)
                prop.transform = false
                setUp(inner.asInstanceOf[Syntax[Any,?,Kind]]) 

            case Disjunction(left, right) =>
                addChildToParent(left.id,s.id)
                addChildToParent(right.id,s.id)
                setUp(left) 
                setUp(right)

            case Sequence(left,right) =>
                addChildToParent(left.id,s.id)
                addChildToParent(right.id,s.id)
                setUp(left.asInstanceOf[Syntax[Any,?,Kind]]) 
                setUp(right.asInstanceOf[Syntax[Any,?,Kind]]) 

            case Recursive(inner) => 
                if(!(rec.contains(s.id))) then
                    addChildToParent(inner.id,s.id)
                    rec.add(s.id)
                    setUp(inner)

            case _ => throw IllegalStateException(s"Unkown Syntax $s")

        }

    def propagate = {
        while(ready.nonEmpty){
            ready.foreach{ (id) => 
                ready.remove(id)
                updateProperties(id)
                idToProperties.get(id) match {
                    case None => ()
                    case Some(prop) =>
                        if (prop.updated){
                            prop.updated = false
                            childToParent.get(id) match {
                                case Some(parentSet) => 
                                    parentSet.foreach{ parentId => 
                                        ready.add(parentId)
                                    }
                                
                                case None => ()
                            }
                        }
                }
            }
        }
    }

    def updateProperties(id: Int):Unit = {
        idToProperties.get(id) match {
            case None => ()
            case Some(prop@Properties(s)) =>
                s match {
                    case Success(v) => ()

                    case Failure() => ()

                    case Elem(k) => 
                        // Parsing Table
                        table.put((s.id,k), Terminal)

                    case Transform(inner,f) =>
                        val child = idToProperties(inner.id)
                        // Productive
                        prop.isProductive = child.isProductive
                        // First
                        prop.first.addAll(child.first)
                        // Nullable
                        prop.nullable = child.nullable
                        // Should-Not-Follow
                        prop.snf.addAll(child.snf)
                        // Conflict
                        prop.hasConflict = child.hasConflict

                        // updated if child is updated
                        prop.updated = true

                        // Parsing Table
                        child.first.foreach { k =>
                            table.put((s.id,k), NonTerminal(inner.id, ApplyF(s.id)))
                        }
                            

                    case Disjunction(left, right) =>
                        val lp = idToProperties(left.id)
                        val rp = idToProperties(right.id)

                        // Productive
                        prop.isProductive = lp.isProductive || rp.isProductive

                        // First
                        prop.update(addAllAndNotify(prop.first,lp.first))
                        prop.update(addAllAndNotify(prop.first,rp.first))

                        // Nullable
                        if(lp.isNullable){
                            prop.nullable = lp.nullable
                        }else{
                            prop.nullable = rp.nullable
                        }

                        // Should-Not-Follow
                        prop.snf.addAll(lp.snf)
                        prop.snf.addAll(rp.snf)
                        if(lp.isNullable){
                            prop.update(addAllAndNotify(prop.snf,rp.first))
                        }
                        if(rp.isNullable){
                            prop.update(addAllAndNotify(prop.snf,lp.first))
                        }

                        // Conflict
                        val both = lp.isNullable && rp.isNullable
                        val has = lp.hasConflict || rp.hasConflict
                        val intersect = lp.first.intersect(rp.first)
                        val ff = intersect.nonEmpty
                        prop.hasConflict = both || has || ff
                        if(both){
                            conflicts.add(LL1Conflict.NullableNullable())
                        }
                        if(ff){
                            conflicts.add(LL1Conflict.FirstFirst(intersect))
                        }

                        // Parsing Table
                        lp.first.foreach { k =>
                            table.put((s.id,k), NonTerminal(left.id, Passed))
                        }
                        rp.first.foreach { k =>
                            table.put((s.id,k), NonTerminal(right.id, Passed))
                        }


                    case Sequence(left,right) =>
                        val lp = idToProperties(left.id)
                        val rp = idToProperties(right.id)
                        // Productive
                        prop.isProductive = lp.isProductive && rp.isProductive
                        // First
                        if(rp.isProductive){
                            prop.update(addAllAndNotify(prop.first,lp.first))
                        }
                        if(lp.isNullable){
                            prop.update(addAllAndNotify(prop.first,rp.first))
                        }
                        // Nullable
                        if(lp.isNullable && rp.isNullable){
                            prop.nullable = Some(Node(lp.nullable.get,rp.nullable.get))
                        }
                        // Should-Not-Follow
                        if(rp.isNullable){
                            prop.update(addAllAndNotify(prop.snf,lp.snf))
                        }
                        if(lp.isProductive){
                            prop.update(addAllAndNotify(prop.snf,lp.snf))
                        }
                        // Conflict
                        val has = lp.hasConflict || rp.hasConflict
                        val intersect = lp.snf.intersect(rp.first)
                        val snfFirst = intersect.nonEmpty
                        prop.hasConflict = has || snfFirst
                        if(snfFirst){
                            conflicts.add(LL1Conflict.SNFFirst(intersect))
                        }

                        // Parsing Table
                        lp.first.foreach { k =>
                            table.put((s.id,k), NonTerminal(left.id, FollowedBy(right.id)))
                        }
                        if (lp.isNullable) {
                            rp.first.foreach { k =>
                                table.put((s.id,k), NonTerminal(right.id, PrependedByNullable(lp.syntax.id)))
                            }
                        }

                    case Recursive(inner) => 
                        val child = idToProperties(inner.id)
                        // Productive
                        prop.isProductive = child.isProductive
                        // First
                        prop.first.addAll(child.first)
                        // Nullable
                        prop.nullable = child.nullable
                        // Should-Not-Follow
                        prop.snf.addAll(child.snf)
                        // Conflict
                        prop.hasConflict = child.hasConflict

                        prop.updated = true

                        // Parsing Table
                        child.first.foreach { k =>
                            table.put((s.id,k), NonTerminal(inner.id, Passed))
                        }

                    case _ => throw IllegalStateException(s"Unkown Syntax $s")
                }
                addToNullableTable(s.id, prop.nullable)
            }
        }


    /**
     *  Add all item in `that` to `thiz`. 
     *  @param thiz set to add item to
     *  @param that set to take item from
     *  
     *  @return `true` if thiz has changed, `false` otherwise
     */
    private def addAllAndNotify[A](thiz: Set[A], that:Set[A]):Boolean = {
        if(that.subsetOf(thiz)){
            false
        } else {
            thiz.addAll(that)
            true
        }
    }

    private def addToNullableTable(id:Int, opt : Option[Nullable]) = {
        opt match {
            case None => ()
            case Some(v) => nullable.put(id, v)
        }
    }


    private def printSetContent(set: Set[?]): String = {
        if set.isEmpty then
            "<None>"
        else
            val h = set.head
            val t = set.tail
            t.foldLeft(s"$h")((str,elem) => str + s", $elem")
    }

    case class Properties(val syntax: Syntax[Any,?,Kind]){
        val first: Set[Kind] = Set()
        val snf: Set[Kind] = Set()
        var transform: Boolean = false
        var nullable:Option[Nullable] = None
        var isProductive:Boolean = true
        var hasConflict = false

        var updated = false

        def isNullable = nullable.nonEmpty

        def update(b: Boolean) = updated = updated || b
    }

    enum LL1Conflict(msg: String) extends Exception(msg) {
        case NullableNullable() extends LL1Conflict(s"Nullable Conflict : Two branches of a disjunction are nullable")
        case FirstFirst(kind: Set[Kind]) extends LL1Conflict(s"First-First Conflict : Two branches of a disjunction have non disjoint first sets : ${printSetContent(kind)}")
        case SNFFirst(kind: Set[Kind]) extends LL1Conflict(s"First-Follow Conflict : The should-not-follow set of the left-hand side of a sequence and the first set of the right-hand side of that sequence are not disjoint: ${printSetContent(kind)}")

        override def toString = s"\n⚠️ $msg ⚠️\n"
    }
}

object Parsing {
    def apply[K](sd: SyntaxDefinition[?,?,K]):PartialParsingTable[K] = {
        val parsing = new Parsing[K]
        parsing(sd)
    }
}

