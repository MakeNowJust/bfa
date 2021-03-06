package bfa

import org.scalatest.{WordSpec, MustMatchers}

class MatchesSpec extends WordSpec with MustMatchers {
  val fixtures =
    List(
      ("", (List(""), List("a", "b"))),
      ("a", (List("a"), List("", "b"))),
      ("a*", (List("", "a", "aa"), List("b", "ab", "ba", "aba"))),
      ("a+", (List("a", "aa"), List("", "b", "ab", "ba", "aba"))),
      ("a?", (List("", "a"), List("b", "aa", "ab"))),
      ("a|b", (List("a", "b"), List("", "aa", "ab", "ba", "bb"))),
      ("(a|b)*",
       (List("", "a", "b", "aa", "ab", "ba", "bb"), List("c", "ac", "bc"))),
      ("(a?)*", (List("", "a", "aa"), List("b", "ab", "ba", "aba"))),
      ("b*(ab*ab*)*",
       (List("", "bb", "aa", "abab", "aabbaabb"), List("a", "ab"))),
      ("(?=a)(a|b)", (List("a"), List("", "b"))),
      ("(?!a)(a|b)", (List("b"), List("", "a"))),
      ("(a|b)(?=(?<=a)b)(a|b)", (List("ab"), List("", "bb"))),
      ("(a|b)(?=(?<!a)b)(a|b)", (List("bb"), List("", "ab"))),
      ("(a|b)(?<=a)", (List("a"), List("", "b"))),
      ("(a|b)(?<!a)", (List("b"), List("", "a"))),
      ("(a|b)(?<=a(?=b))(a|b)", (List("ab"), List("aa"))),
      ("(a|b)(?<=a(?!b))(a|b)", (List("aa"), List("ab"))),
      ("(a(a|b)(?<=(a|b)*b(?=a(a|b)*))|aa)b", (List("aab"), List("aaa"))),
      ("(a(?=a)|a)b", (List("ab"), List("b"))),
    )

  "AST#matches" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            Parser.parse(s).get.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            Parser.parse(s).get.matches(fail) must not be (true)
          }
        }
    }
  }

  "MBFA#matches" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        val mbfa = MBFA.from(Parser.parse(s).get)
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            mbfa.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            mbfa.matches(fail) must not be (true)
          }
        }
    }
  }

  "DFA#matches" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        val dfa = MBFA.from(Parser.parse(s).get).toDFA
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            dfa.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            dfa.matches(fail) must not be (true)
          }
        }
    }
  }

  "DFA#toRegExp" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        val node = MBFA.from(Parser.parse(s).get).toDFA.toRegExp
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            node.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            node.matches(fail) must not be (true)
          }
        }
    }
  }

  "DFA#minimize" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        val dfa = MBFA.from(Parser.parse(s).get).toDFA.minimize
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            dfa.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            dfa.matches(fail) must not be (true)
          }
        }
    }
  }

  "DFA#minimize -> DFA#toRegExp" must {
    fixtures.foreach {
      case (s, (oks, fails)) =>
        val node = MBFA.from(Parser.parse(s).get).toDFA.minimize.toRegExp
        oks.foreach { ok =>
          s"""match "$s" against "$ok"""" in {
            node.matches(ok) must be(true)
          }
        }

        fails.foreach { fail =>
          s"""not match "$s" against "$fail"""" in {
            node.matches(fail) must not be (true)
          }
        }
    }
  }
}
