package bfa

sealed abstract class RefExpr {
  override def toString: String = this match {
    case RefExpr.Ref(n) => s"#$n"
    case RefExpr.Var(v) => v.name
  }
}

object RefExpr {
  final case class Ref(n: Int) extends RefExpr
  final case class Var(v: Symbol) extends RefExpr
}

case class BFA(
    init: DNF[RefExpr],
    trans: Map[(Symbol, Char), DNF[RefExpr]],
    last: Set[Symbol]
) {
  import RefExpr._

  def update(e: DNF[Symbol],
             c: Char,
             f: RefExpr => DNF[Symbol]): DNF[Symbol] = {
    e.replace { v =>
      trans.getOrElse((v, c), DNF.zero).replace(f)
    }
  }
}

case class MBFA(
    main: BFA,
    subs: IndexedSeq[BFA],
) {
  implicit object FromSymbolOnSymbol extends DNF.From[Symbol] {
    type Var = Symbol

    def from(v: Symbol): DNF[Symbol] = DNF(Set((Set(v), Set.empty)))
  }

  private case class Run(mainExpr: DNF.Full[Symbol],
                         subExprs: IndexedSeq[DNF.Full[Symbol]]) {
    def update(c: Char): Run = {
      lazy val resolveRefExpr: RefExpr => DNF[Symbol] = {
        case RefExpr.Ref(n) => {
          val sub = subs(n)
          val subExpr = updateSub(n)

          subExpr.replace { v =>
            if (sub.last.contains(v)) DNF.one else DNF.from(v)
          }
        }
        case RefExpr.Var(v) => DNF.from(v)
      }

      lazy val updateSub: Int => DNF[Symbol] = util.memoize { n =>
        subs(n).update(subExprs(n).dnf, c, resolveRefExpr)
      }

      Run(
        main.update(mainExpr.dnf, c, resolveRefExpr).toFull,
        (0 until subs.length).map(updateSub(_).toFull),
      )
    }
  }

  private def initRun: Run = {
    lazy val resolveRefExpr: RefExpr => DNF[Symbol] = {
      case RefExpr.Ref(n) => initSub(n)
      case RefExpr.Var(v) => DNF.from(v)
    }

    lazy val initSub: Int => DNF[Symbol] = util.memoize { n =>
      subs(n).init.replace(resolveRefExpr)
    }

    Run(
      main.init.replace(resolveRefExpr).toFull,
      (0 until subs.length).map(initSub(_).toFull),
    )
  }

  def matches(s: String): Boolean =
    s.foldLeft(initRun) { case (r, c) => r.update(c) }
      .mainExpr
      .dnf
      .evaluate(main.last)

  def toDFA: DFA = {
    def step(run: Run,
             id: Int,
             qMap: Map[Run, Symbol]): (Int,
                                       Map[Run, Symbol],
                                       Symbol,
                                       Map[(Symbol, Char), Symbol],
                                       Set[Symbol]) =
      qMap
        .get(run)
        .map { q =>
          (id, qMap, q, Map[(Symbol, Char), Symbol](), Set[Symbol]())
        }
        .getOrElse {
          val q = Symbol(s"v$id")
          val id1 = id + 1
          val qMap1 = qMap + (run -> q)

          val symbols = run.subExprs.foldLeft(run.mainExpr.dnf.symbols) {
            case (vs, subExpr) => vs | subExpr.dnf.symbols
          }
          val mainChars = main.trans.keySet
            .filter { case (q, _) => symbols.contains(q) }
            .map(_._2)
          val chars = subs.foldLeft(mainChars) {
            case (chars, sub) =>
              chars | sub.trans.keySet
                .filter { case (q, _) => symbols.contains(q) }
                .map(_._2)
          }

          val l: Set[Symbol] = run.mainExpr.dnf.evaluate(main.last) match {
            case true  => Set(q)
            case false => Set.empty
          }

          val (id2, qMap2, t, l1) =
            chars.foldLeft((id1, qMap1, Map[(Symbol, Char), Symbol](), l)) {
              case ((id2, qMap2, t, l1), c) =>
                val (id3, qMap3, p, pt, pl) = step(run.update(c), id2, qMap2)
                (id3, qMap3, t + ((q, c) -> p) ++ pt, l1 | pl)
            }
          (id2, qMap2, q, t, l1)
        }

    val (_, _, i, t, l) = step(initRun, 1, Map.empty)
    DFA(i, t, l)
  }
}

object MBFA {
  import AST._
  import RefExpr._

  implicit object FromSymbolOnRefExpr extends DNF.From[Symbol] {
    type Var = RefExpr

    def from(v: Symbol): DNF[RefExpr] = DNF(Set((Set(Var(v)), Set.empty)))
  }

  implicit object FromInt extends DNF.From[Int] {
    type Var = RefExpr

    def from(v: Int): DNF[RefExpr] = DNF(Set((Set(Ref(v)), Set.empty)))
  }

  def from(node: AST): MBFA = {
    val Convert(_, subs, init, trans, last, aheadTrans, aheadLast) =
      convert(node, 1, IndexedSeq.empty)
    MBFA(BFA(init, trans ++ aheadTrans, last | aheadLast), subs.map {
      case BFA(i, t, l) => BFA(i, t ++ aheadTrans, l)
    })
  }

  private case class Convert(
      id: Int,
      subs: IndexedSeq[BFA],
      init: DNF[RefExpr],
      trans: Map[(Symbol, Char), DNF[RefExpr]],
      last: Set[Symbol],
      aheadTrans: Map[(Symbol, Char), DNF[RefExpr]],
      aheadLast: Set[Symbol]
  )

  private def convert(node: AST, id: Int, subs: IndexedSeq[BFA]): Convert = {
    def nextId(id: Int) = (id + 1, Symbol(s"v$id"))

    node match {
      case Alt(left, right) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(left, id, subs)
        val Convert(id2, subs2, i2, t2, l2, at2, al2) =
          convert(right, id1, subs1)

        Convert(id2, subs2, i1 ∨ i2, t1 ++ t2, l1 | l2, at1 ++ at2, al1 | al2)
      }

      case Concat(left, right) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(left, id, subs)
        val Convert(id2, subs2, i2, t2, l2, at2, al2) =
          convert(right, id1, subs1)

        val f: RefExpr => DNF[RefExpr] = {
          case Ref(n) => DNF.from(n)
          case Var(v) => if (l1.contains(v)) i2 else DNF.from(v)
        }

        val i3 = i1.replace(f)
        val t3 = t1.mapValues(_.replace(f)) ++ t2

        Convert(id2, subs2, i3, t3, l2, at1 ++ at2, al1 | al2)
      }

      case PositiveLookAhead(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        Convert(id1,
                subs1,
                i1 ∧ DNF.from(v),
                Map.empty,
                Set(v),
                at1 ++ t1,
                al1 | l1)
      }

      case NegativeLookAhead(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        Convert(id1,
                subs1,
                i1.invert ∧ DNF.from(v),
                Map.empty,
                Set(v),
                at1 ++ t1,
                al1 | l1)
      }

      case PositiveLookBehind(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        Convert(id2,
                subs1 :+ BFA(i1, t1, l1),
                DNF.from(v) ∧ DNF.from(subs1.length),
                Map.empty,
                Set(v),
                at1,
                al1)
      }

      case NegativeLookBehind(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        Convert(id2,
                subs1 :+ BFA(i1, t1, l1),
                DNF.from(v) ∧ DNF.from(subs1.length).invert,
                Map.empty,
                Set(v),
                at1,
                al1)
      }

      case Star(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        val f: RefExpr => DNF[RefExpr] = {
          case Ref(n) => DNF.from(n)
          case Var(v) => if (l1.contains(v)) DNF.from(v) ∨ i1 else DNF.from(v)
        }

        val i2 = i1.replace(f) ∨ DNF.from(v)
        val t2 = t1.mapValues(_.replace(f))

        Convert(id2, subs, i2, t2, l1 + v, at1, al1)
      }

      case Plus(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)

        val f: RefExpr => DNF[RefExpr] = {
          case Ref(n) => DNF.from(n)
          case Var(v) => if (l1.contains(v)) DNF.from(v) ∨ i1 else DNF.from(v)
        }

        val i2 = i1.replace(f)
        val t2 = t1.mapValues(_.replace(f))

        Convert(id1, subs, i2, t2, l1, at1, al1)
      }

      case Quest(node) => {
        val Convert(id1, subs1, i1, t1, l1, at1, al1) = convert(node, id, subs)
        val (id2, v) = nextId(id1)

        Convert(id2, subs, i1 ∨ DNF.from(v), t1, l1 + v, at1, al1)
      }

      case Literal(c) => {
        val (id1, i) = nextId(id)
        val (id2, l) = nextId(id1)

        Convert(id2,
                subs,
                DNF.from(i),
                Map((i, c) -> DNF.from(l)),
                Set(l),
                Map.empty,
                Set.empty)
      }

      case Empty => {
        val (id1, v) = nextId(id)

        Convert(id1, subs, DNF.from(v), Map.empty, Set(v), Map.empty, Set.empty)
      }
    }
  }
}
