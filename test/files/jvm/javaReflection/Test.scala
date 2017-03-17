/**
Interesting aspects of Java reflection applied to scala classes. TL;DR: you should not use
getSimpleName / getCanonicalName / isAnonymousClass / isLocalClass / isSynthetic.

  - Some methods in Java reflection assume a certain structure in the class names. Scalac
    can produce class files that don't respect this structure. Certain methods in reflection
    therefore give surprising answers or may even throw an exception.

    In particular, the method "getSimpleName" assumes that classes are named after the Java spec
      http://docs.oracle.com/javase/specs/jls/se8/html/jls-13.html#jls-13.1

    Consider the following Scala example:
      class A { object B { class C } }

    The classfile for C has the name "A$B$C", while the classfile for the module B has the
    name "A$B$".

    For "cClass.getSimpleName, the implementation first strips the name of the enclosing class,
    which produces "C". The implementation then expects a "$" character, which is missing, and
    throws an InternalError.

    Consider another example:
      trait T
      class A  { val x = new T {} }
      object B { val x = new T {} }

    The anonymous classes are named "A$$anon$1" and "B$$anon$2". If you call "getSimpleName",
    you get "$anon$1" (leading $) and "anon$2" (no leading $).

  - There are certain other methods in the Java reflection API that depend on getSimpleName.
    These should be avoided, they yield unexpected results:

    - isAnonymousClass is always false. Scala-defined classes are never anonymous for Java
      reflection. Java reflection inspects the class name to decide whether a class is
      anonymous, based on the name spec referenced above.
      Also, the implementation of "isAnonymousClass" calls "getSimpleName", which may throw.

    - isLocalClass: should be true true for local classes (nested classes that are not
      members), but not for anonymous classes. Since "isAnonymousClass" is always false,
      Java reflection thinks that all Scala-defined anonymous classes are local.
      The implementation may also throw, since it uses "isAnonymousClass":
        class A { object B { def f = { class KB; new KB } } }
        (new A).B.f.getClass.isLocalClass // boom

    - getCanonicalName: uses "getSimpleName" in the implementation. In the first example,
      cClass.getCanonicalName also fails with an InternalError.

  - Scala-defined classes are never synthetic for Java reflection. The implementation
    checks for the SYNTHETEIC flag, which does not seem to be added by scalac (maybe this
    will change some day).
*/

object Test {

  def tr[T](m: => T): String = try {
    val r = m
    if (r == null) "null"
    else r.toString
  } catch { case e: InternalError => e.getMessage }

  def assertNotAnonymous(c: Class[_]) = {
    val an = try {
      c.isAnonymousClass
    } catch {
      // isAnonymousClass is implemented using getSimpleName, which may throw.
      case e: InternalError => false
    }
    assert(!an, c)
  }

  def ruleMemberOrLocal(c: Class[_]) = {
    // if it throws, then it's because of the call from isLocalClass to isAnonymousClass.
    // we know that isAnonymousClass is always false, so it has to be a local class.
    val loc = try { c.isLocalClass } catch { case e: InternalError => true }
    if (loc)
      assert(!c.isMemberClass, c)
    if (c.isMemberClass)
      assert(!loc, c)
  }

  def ruleMemberDeclaring(c: Class[_]) = {
    if (c.isMemberClass)
      assert(c.getDeclaringClass.getDeclaredClasses.toList.map(_.getName) contains c.getName)
  }

  def ruleScalaAnonClassIsLocal(c: Class[_]) = {
    if (c.getName contains "$anon$")
      assert(c.isLocalClass, c)
  }

  def ruleScalaAnonFunInlineIsLocal(c: Class[_]) = {
    // exclude lambda classes generated by delambdafy:method. nested closures have both "anonfun" and "lambda".
    if (c.getName.contains("$anonfun$") && !c.getName.contains("$lambda$"))
      assert(c.isLocalClass, c)
  }

  def ruleScalaAnonFunMethodIsToplevel(c: Class[_]) = {
    if (c.getName.contains("$lambda$"))
      assert(c.getEnclosingClass == null, c)
  }

  def showClass(name: String) = {
    val c = Class.forName(name)

    println(s"${c.getName} / ${tr(c.getCanonicalName)} (canon) / ${tr(c.getSimpleName)} (simple)")
    println( "- declared cls: "+ c.getDeclaredClasses.toList.sortBy(_.getName))
    println(s"- enclosing   : ${c.getDeclaringClass} (declaring cls) / ${c.getEnclosingClass} (cls) / ${c.getEnclosingConstructor} (constr) / ${c.getEnclosingMethod} (meth)")
    println(s"- properties  : ${tr(c.isLocalClass)} (local) / ${c.isMemberClass} (member)")

    assertNotAnonymous(c)
    assert(!c.isSynthetic, c)

    ruleMemberOrLocal(c)
    ruleMemberDeclaring(c)
    ruleScalaAnonClassIsLocal(c)
    ruleScalaAnonFunInlineIsLocal(c)
    ruleScalaAnonFunMethodIsToplevel(c)
  }

  def main(args: Array[String]): Unit = {
    def isAnonFunClassName(s: String) = s.contains("$anonfun$") || s.contains("$lambda$")

    val classfiles = new java.io.File(sys.props("partest.output")).listFiles().toList.map(_.getName).collect({
      // exclude files from Test.scala, just take those from Classes_1.scala
      case s if !s.startsWith("Test") && s.endsWith(".class") => s.substring(0, s.length - 6)
    }).sortWith((a, b) => {
      // sort such that first there are all anonymous functions, then all other classes.
      // within those categories, sort lexically.
      // this makes the check file smaller: it differs for anonymous functions between -Ydelambdafy:inline/method.
      // the other classes are the same.
      if (isAnonFunClassName(a)) !isAnonFunClassName(b) || a < b
      else !isAnonFunClassName(b) && a < b
    })

    classfiles foreach showClass
  }
}