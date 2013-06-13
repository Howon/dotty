package dotty.tools
package dotc
package ast

import core._
import util.Positions._, Types._, Contexts._, Constants._, Names._, NameOps._, Flags._
import SymDenotations._, Symbols._, StdNames._, Annotations._, Trees._
import TreeInfo._
import Decorators._
import language.higherKinds
import collection.mutable.ListBuffer

object desugar {

  import untpd._

  private type VarInfo = (NameTree, Tree)

  object Mode extends Enumeration {
    val Type, Expr, Pattern = Value
  }

  def valDef(vdef: ValDef)(implicit ctx: Context): Tree = {
    val ValDef(mods, name, tpt, rhs) = vdef
    if (!ctx.owner.isClass || (mods is Private)) vdef
    else {
      val lname = name.toLocalName
      val field = vdef.derivedValDef(mods, lname, tpt, rhs)
      val getter = vdef.derivedDefDef(mods, name, Nil, Nil, tpt, Ident(lname))
      if (!(mods is Mutable)) Thicket(field, getter)
      else {
        val setterParam = makeSyntheticParameter(tpt = TypeTree(field))
        val setter = vdef.derivedDefDef(
          mods, name.getterToSetter, Nil, (setterParam :: Nil) :: Nil, EmptyTree, refOfDef(setterParam))
        Thicket(field, getter, setter)
      }
    }
  }

  def defDef(meth: DefDef, isPrimaryConstructor: Boolean = false): DefDef = {
    val DefDef(mods, name, tparams, vparamss, tpt, rhs) = meth
    val epbuf = new ListBuffer[ValDef]
    val tparams1 = tparams mapConserve {
      case tparam @ TypeDef(mods, name, ContextBounds(tbounds, cxbounds)) =>
        for (cxbound <- cxbounds) {
          val paramFlags: FlagSet = if (isPrimaryConstructor) PrivateLocalParamAccessor else Param
          val epname = (nme.EVIDENCE_PARAM_PREFIX.toString + epbuf.length).toTermName
          epbuf +=
            ValDef(Modifiers(paramFlags | Implicit), epname, cxbound, EmptyTree)
        }
        tparam.derivedTypeDef(mods, name, tbounds, tparam.tparams)
      case tparam =>
        tparam
    }
    epbuf.toList match {
      case Nil =>
        meth
      case evidenceParams =>
        val vparamss1 = vparamss.reverse match {
          case (vparams @ (vparam :: _)) :: rvparamss if vparam.mods is Implicit =>
            ((vparams ++ evidenceParams) :: rvparamss).reverse
          case _ =>
            vparamss :+ evidenceParams
        }
        meth.derivedDefDef(mods, name, tparams1, vparamss1, tpt, rhs)
    }
  }

  def typeDef(tdef: TypeDef)(implicit ctx: Context): Tree = {
    val TypeDef(mods, name, rhs) = tdef
    if (mods is PrivateLocalParamAccessor) {
      val tparam = tdef.derivedTypeDef(
        tdef.mods &~ PrivateLocal | ExpandedName, tdef.name.expandedName(ctx.owner), tdef.rhs, tdef.tparams)
      val alias = tdef.derivedTypeDef(
        Modifiers(PrivateLocal | Synthetic), tdef.name, refOfDef(tparam))
      Thicket(tparam, alias)
    }
    else tdef
  }

  private val synthetic = Modifiers(Synthetic)

  def classDef(cdef: ClassDef)(implicit ctx: Context): Tree = {
    val ClassDef(
      mods, name, impl @ Template(constr0, parents, self, body)) = cdef

    val constr1 = defDef(constr0, isPrimaryConstructor = true)

    val tparams = constr1.tparams.map(tparam => tparam.derivedTypeDef(
      Modifiers(Param), tparam.name, tparam.rhs, tparam.tparams))

    // ensure parameter list is non-empty
    val vparamss =
      if (constr1.vparamss.isEmpty) {
        if (mods is Case)
          ctx.error("case class needs to have at least one parameter list", cdef.pos)
        ListOfNil
      } else
        constr1.vparamss.nestedMap(vparam => vparam.derivedValDef(
          Modifiers(Param), vparam.name, vparam.tpt, vparam.rhs))

    val constr = constr1.derivedDefDef(
      constr1.mods, constr1.name, tparams, vparamss, constr1.tpt, constr1.rhs)

    val classTypeRef = {
      val tycon = Ident(cdef.name)
      val tparams = cdef.impl.constr.tparams
      if (tparams.isEmpty) tycon else AppliedTypeTree(tycon, tparams map refOfDef)
    }

    val creatorExpr = New(classTypeRef, vparamss nestedMap refOfDef)

    val caseClassMeths =
      if (mods is Case) {
        val caseParams = vparamss.head.toArray
        def syntheticProperty(name: TermName, rhs: Tree) =
          DefDef(synthetic, name, Nil, Nil, EmptyTree, rhs)
        val isDefinedMeth = syntheticProperty(nme.isDefined, Literal(Constant(true)))
        val productArityMeth = syntheticProperty(nme.productArity, Literal(Constant(caseParams.length)))
        val productElemMeths = for (i <- 0 until caseParams.length) yield
          syntheticProperty(("_" + (i + 1)).toTermName, Select(This(EmptyTypeName), caseParams(i).name))
        val copyMeths =
          if (mods is Abstract) Nil
          else {
            val copyFirstParams = vparamss.head.map(vparam =>
              vparam.derivedValDef(vparam.mods, vparam.name, vparam.tpt, refOfDef(vparam)))
            val copyRestParamss = vparamss.tail.nestedMap(vparam =>
              vparam.derivedValDef(vparam.mods, vparam.name, vparam.tpt, EmptyTree))
            DefDef(synthetic, nme.copy, tparams, copyFirstParams :: copyRestParamss, EmptyTree, creatorExpr) :: Nil
          }
        copyMeths ::: isDefinedMeth :: productArityMeth :: productElemMeths.toList
      }
      else Nil

    val caseCompanions =
      if (mods is Case) {
        val parent =
          if (tparams.nonEmpty) ref(defn.AnyRefAlias.typeConstructor)
          else (vparamss :\ classTypeRef) ((vparams, restpe) => Function(vparams map (_.tpt), restpe))
        val applyMeths =
          if (mods is Abstract) Nil
          else DefDef(synthetic, nme.apply, tparams, vparamss, EmptyTree, creatorExpr) :: Nil
        val unapplyMeth = {
          val unapplyParam = makeSyntheticParameter(tpt = classTypeRef)
          DefDef(synthetic, nme.unapply, tparams, (unapplyParam :: Nil) :: Nil, EmptyTree, This(EmptyTypeName))
        }
        moduleDef(
          ModuleDef(
            Modifiers(Synthetic), name.toTermName,
            Template(emptyConstructor, parent :: Nil, EmptyValDef(), applyMeths ::: unapplyMeth :: Nil))
        ).toList
      }
      else Nil

    val implicitWrappers =
      if (mods is Implicit) {
        if (ctx.owner is Package)
          ctx.error("implicit classes may not be toplevel", cdef.pos)
        if (mods is Case)
          ctx.error("implicit classes may not case classes", cdef.pos)
        DefDef(Modifiers(Synthetic | Implicit), name.toTermName,
          tparams, vparamss, EmptyTree, creatorExpr) :: Nil
      }
      else Nil

    val cdef1 = cdef.derivedClassDef(mods, name,
      impl.derivedTemplate(constr, parents, self,
        constr1.tparams ::: constr1.vparamss.flatten ::: body ::: caseClassMeths))
    Thicket.make(cdef1 :: caseCompanions ::: implicitWrappers)
  }

  /** Expand to:
   *  <module> val name: name$ = New(name$)
   *  <module> final class name$ extends parents { self: name.type => body }
   */
  def moduleDef(mdef: ModuleDef)(implicit ctx: Context): Tree = {
    val ModuleDef(mods, name, tmpl @ Template(constr, parents, self, body)) = mdef
    val clsName = name.moduleClassName
    val clsRef = Ident(clsName)
    val modul = ValDef(mods | ModuleCreationFlags, name, clsRef, New(clsRef, Nil))
    val clsSelf = self.derivedValDef(self.mods, self.name, SingletonTypeTree(Ident(name)), self.rhs)
    val clsTmpl = tmpl.derivedTemplate(constr, parents, clsSelf, body)
    val cls = ClassDef(mods.toTypeFlags & AccessFlags | ModuleClassCreationFlags, clsName, clsTmpl)
    Thicket(cls, valDef(modul))
  }

  def memberDef(tree: Tree)(implicit ctx: Context): Tree = tree match {
    case tree: ValDef => valDef(tree)
    case tree: TypeDef => typeDef(tree)
    case tree: DefDef => defDef(tree)
    case tree: ClassDef => classDef(tree)
    case tree: ModuleDef => moduleDef(tree)
  }

  def apply(tree: Tree, mode: Mode.Value)(implicit ctx: Context): Tree = {

    def labelDefAndCall(lname: TermName, rhs: Tree, call: Tree) = {
      val ldef = DefDef(Modifiers(Label), lname, Nil, ListOfNil, TypeTree(), rhs)
      Block(ldef, call)
    }

    def derivedValDef(mods: Modifiers, named: NameTree, tpt: Tree, rhs: Tree) =
      ValDef(mods, named.name.asTermName, tpt, rhs).withPos(named.pos)

    /** Translate infix operation expression  left op right
     */
    def makeBinop(left: Tree, op: Name, right: Tree): Tree = {
      def assignToNamedArg(arg: Tree) = arg match {
        case Assign(Ident(name), rhs) => arg.derivedNamedArg(name, rhs)
        case _ => arg
      }
      if (isLeftAssoc(op)) {
        val args: List[Tree] = right match {
          case Parens(arg) => assignToNamedArg(arg) :: Nil
          case Tuple(args) => args mapConserve assignToNamedArg
          case _ => right :: Nil
        }
        Apply(Select(left, op), args)
      } else {
        val x = ctx.freshName().toTermName
        Block(
          ValDef(Modifiers(Synthetic), x, TypeTree(), left),
          Apply(Select(right, op), Ident(x)))
      }
    }

    /** Make closure corresponding to function  params => body */
    def makeClosure(params: List[ValDef], body: Tree) =
      Block(
        DefDef(Modifiers(Synthetic), nme.ANON_FUN, Nil, params :: Nil, EmptyTree, body),
        Closure(Nil, Ident(nme.ANON_FUN)))

    /** Make closure corresponding to partial function  { cases } */
    def makeCaseClosure(cases: List[CaseDef]) = {
      val param = makeSyntheticParameter()
      makeClosure(param :: Nil, Match(Ident(param.name), cases))
    }

    /** Create tree for for-comprehension <for (enums) do body> or
     *   <for (enums) yield body> where mapName and flatMapName are chosen
     *  corresponding to whether this is a for-do or a for-yield.
     *  The creation performs the following rewrite rules:
     *
     *  1.
     *
     *    for (P <- G) E   ==>   G.foreach (P => E)
     *
     *     Here and in the following (P => E) is interpreted as the function (P => E)
     *     if P is a variable pattern and as the partial function { case P => E } otherwise.
     *
     *  2.
     *
     *    for (P <- G) yield E  ==>  G.map (P => E)
     *
     *  3.
     *
     *    for (P_1 <- G_1; P_2 <- G_2; ...) ...
     *      ==>
     *    G_1.flatMap (P_1 => for (P_2 <- G_2; ...) ...)
     *
     *  4.
     *
     *    for (P <- G; E; ...) ...
     *      =>
     *    for (P <- G.filter (P => E); ...) ...
     *
     *  5. For any N:
     *
     *    for (P_1 <- G; P_2 = E_2; val P_N = E_N; ...)
     *      ==>
     *    for (TupleN(P_1, P_2, ... P_N) <-
     *      for (x_1 @ P_1 <- G) yield {
     *        val x_2 @ P_2 = E_2
     *        ...
     *        val x_N & P_N = E_N
     *        TupleN(x_1, ..., x_N)
     *      } ...)
     *
     *    If any of the P_i are variable patterns, the corresponding `x_i @ P_i' is not generated
     *    and the variable constituting P_i is used instead of x_i
     *
     *  @param mapName      The name to be used for maps (either map or foreach)
     *  @param flatMapName  The name to be used for flatMaps (either flatMap or foreach)
     *  @param enums        The enumerators in the for expression
     *  @param body         The body of the for expression
     */
    def makeFor(mapName: TermName, flatMapName: TermName, enums: List[Tree], body: Tree): Tree = {

      /** Make a function value pat => body.
       *  If pat is a var pattern id: T then this gives (id: T) => body
       *  Otherwise this gives { case pat => body }
       */
      def makeLambda(pat: Tree, body: Tree): Tree = pat match {
        case VarPattern(named, tpt) =>
          makeClosure(derivedValDef(Modifiers(Param), named, tpt, EmptyTree) :: Nil, body)
        case _ =>
          makeCaseClosure(CaseDef(pat, EmptyTree, body) :: Nil)
      }

      /** If `pat` is not yet a `Bind` wrap it in one with a fresh name
       */
      def makeBind(pat: Tree): Tree = pat match {
        case Bind(_, _) => pat
        case _ => Bind(ctx.freshName().toTermName, pat)
      }

      /** Is pattern `pat` irrefutable when matched against `rhs`?
       *  We only can do a simple syntactic check here; a more refined check
       *  is done later prompted by the presence of a "withFilterIfRefutable" call.
       */
      def isIrrefutable(pat: Tree, rhs: Tree): Boolean = {
        def matchesTuple(pats: List[Tree], rhs: Tree): Boolean = rhs match {
          case Tuple(trees) => (pats corresponds trees)(isIrrefutable)
          case Parens(rhs1) => matchesTuple(pats, rhs1)
          case Block(_, rhs1) => matchesTuple(pats, rhs1)
          case If(_, thenp, elsep) => matchesTuple(pats, thenp) && matchesTuple(pats, elsep)
          case Match(_, cases) => cases forall (matchesTuple(pats, _))
          case CaseDef(_, _, rhs1) => matchesTuple(pats, rhs)
          case Throw(_) => true
          case _ => false
        }
        pat match {
          case Bind(_, pat1) => isIrrefutable(pat1, rhs)
          case Parens(pat1) => isIrrefutable(pat1, rhs)
          case Tuple(pats) => matchesTuple(pats, rhs)
          case _ => isVarPattern(pat)
        }
      }

      /** Make a pattern filter:
       *    rhs.withFilterIfRefutable { case pat => true case _ => false }
       */
      def makePatFilter(rhs: Tree, pat: Tree): Tree = {
        val cases = List(
          CaseDef(pat, EmptyTree, Literal(Constant(true))),
          CaseDef(Ident(nme.WILDCARD), EmptyTree, Literal(Constant(false))))
        Apply(Select(rhs, nme.withFilterIfRefutable), Match(EmptyTree, cases))
      }

      /** rhs.name with a pattern filter on rhs unless `pat` is irrefutable when
       *  matched against `rhs`.
       */
      def rhsSelect(rhs: Tree, name: TermName, pat: Tree) = {
        val rhs1 = if (isIrrefutable(pat, rhs)) rhs else makePatFilter(rhs, pat)
        Select(rhs1, name)
      }

      enums match {
        case (enum @ GenFrom(pat, rhs)) :: Nil =>
          Apply(rhsSelect(rhs, mapName, pat), makeLambda(pat, body))
        case GenFrom(pat, rhs) :: (rest @ (GenFrom(_, _) :: _)) =>
          val cont = makeFor(mapName, flatMapName, rest, body)
          Apply(rhsSelect(rhs, flatMapName, pat), makeLambda(pat, cont))
        case (enum @ GenFrom(pat, rhs)) :: (rest @ GenAlias(_, _) :: _) =>
          val (valeqs, rest1) = rest.span(_.isInstanceOf[GenAlias])
          val pats = valeqs map { case GenAlias(pat, _) => pat }
          val rhss = valeqs map { case GenAlias(_, rhs) => rhs }
          val defpat1 = makeBind(pat)
          val defpats = pats map makeBind
          val pdefs = (defpats, rhss).zipped map (makePatDef(Modifiers(), _, _))
          val ids = (defpat1 :: defpats) map { case Bind(name, _) => Ident(name) }
          val rhs1 = makeFor(nme.map, nme.flatMap, GenFrom(defpat1, rhs) :: Nil, Block(pdefs, makeTuple(ids)))
          val allpats = pat :: pats
          val vfrom1 = GenFrom(makeTuple(allpats), rhs1)
          makeFor(mapName, flatMapName, vfrom1 :: rest1, body)
        case (enum @ GenFrom(pat, rhs)) :: test :: rest =>
          val filtered = Apply(rhsSelect(rhs, nme.withFilter, pat), makeLambda(pat, test))
          makeFor(mapName, flatMapName, GenFrom(pat, filtered) :: rest, body)
        case _ =>
          EmptyTree //may happen for erroneous input
      }
    }

    def makeAnnotated(cls: Symbol, tree: Tree) =
      Annotated(TypedSplice(tpd.New(cls.typeConstructor)), tree)

    /** Returns list of all pattern variables, possibly with their types,
     *  without duplicates
     */
    def getVariables(tree: Tree): List[VarInfo] =
      getVars(new ListBuffer[VarInfo], tree).toList

    /** In case there is exactly one variable x_1 in pattern
     *   val/var p = e  ==>  val/var x_1 = (e: @unchecked) match (case p => (x_1))
     *
     *   in case there are zero or more than one variables in pattern
     *   val/var p = e  ==>  private synthetic val t$ = (e: @unchecked) match (case p => (x_1, ..., x_N))
     *                   val/var x_1 = t$._1
     *                   ...
     *                  val/var x_N = t$._N
     *  If the original pattern variable carries a type annotation, so does the corresponding
     *  ValDef.
     */
    def makePatDef(mods: Modifiers, pat: Tree, rhs: Tree): Tree = pat match {
      case VarPattern(named, tpt) =>
        derivedValDef(mods, named, tpt, rhs)
      case _ =>
        val rhsUnchecked = makeAnnotated(defn.UncheckedAnnot, rhs)
        val vars = getVariables(pat)
        val ids = for ((named, _) <- vars) yield Ident(named.name)
        val caseDef = CaseDef(pat, EmptyTree, makeTuple(ids))
        val matchExpr = Match(rhsUnchecked, caseDef :: Nil)
        vars match {
          case (named, tpt) :: Nil =>
            derivedValDef(mods, named, tpt, matchExpr)
          case _ =>
            val tmpName = ctx.freshName().toTermName
            val patMods = Modifiers(PrivateLocal | Synthetic | (mods.flags & Lazy))
            val firstDef = ValDef(patMods, tmpName, TypeTree(), matchExpr)
            def selector(n: Int) = Select(Ident(tmpName), ("_" + n).toTermName)
            val restDefs =
              for (((named, tpt), n) <- vars.zipWithIndex)
              yield derivedValDef(mods, named, tpt, selector(n))
            Thicket.make(firstDef :: restDefs)
        }
    }

    def isPatternVar(id: Ident) =
      mode == Mode.Pattern && isVarPattern(id) && id.name != nme.WILDCARD

    // begin desugar
    val tree1 = tree match { // todo: move general tree desugaring to typer, and keep only untyped trees here?
      case id @ Ident(_) if isPatternVar(id) =>
        Bind(id.name, Ident(nme.WILDCARD))
      case Typed(id @ Ident(_), tpt) if isPatternVar(id) =>
        Bind(id.name, Typed(Ident(nme.WILDCARD), tpt)).withPos(id.pos)
      case New(templ: Template) =>
        val x = tpnme.ANON_CLASS
        val clsDef = ClassDef(Modifiers(Final), x, templ)
        Block(clsDef, New(Ident(x), Nil))
      case Assign(Apply(fn, args), rhs) =>
        Apply(Select(fn, nme.update), args :+ rhs)
      case If(cond, thenp, EmptyTree) =>
        If(cond, thenp, unitLiteral)
      case Match(EmptyTree, cases) =>
        makeCaseClosure(cases)
      case tree: MemberDef =>
        memberDef(tree)
      case SymbolLit(str) =>
        New(ref(defn.SymbolClass.typeConstructor), (Literal(Constant(str)) :: Nil) :: Nil)
      case InterpolatedString(id, strs, elems) =>
        Apply(Select(Apply(Ident(nme.StringContext), strs), id), elems)
      case Function(args, body) =>
        if (mode == Mode.Type) // FunctionN[args: _*, body]
          AppliedTypeTree(
            ref(defn.FunctionClass(args.length).typeConstructor),
            args :+ body)
        else
          makeClosure(args.asInstanceOf[List[ValDef]], body)
      case InfixOp(l, op, r) =>
        mode match {
          case Mode.Expr => // l.op(r), or val x = r; l.op(x), plus handle named args specially
            makeBinop(l, op, r)
          case Mode.Pattern => // op(l, r)
            Apply(Ident(op), l :: r :: Nil)
          case Mode.Type => // op[l, r]
            AppliedTypeTree(Ident(op), l :: r :: Nil)
        }
      case PostfixOp(t, op) =>
        if (mode == Mode.Type && op == nme.raw.STAR)
          AppliedTypeTree(ref(defn.RepeatedParamType), t)
        else {
          assert(mode == Mode.Expr)
          if (op == nme.WILDCARD) tree // desugar later by eta expansion
          else Select(t, op)
        }
      case PrefixOp(op, t) =>
        if (mode == Mode.Type && op == nme.ARROWkw)
          AppliedTypeTree(ref(defn.ByNameParamClass.typeConstructor), t)
        else
          Select(t, nme.UNARY_PREFIX ++ op)
      case Parens(t) =>
        t
      case Tuple(ts) =>
        def PairTypeTree(l: Tree, r: Tree) =
          AppliedTypeTree(ref(defn.PairClass.typeConstructor), l :: r :: Nil)
        if (mode == Mode.Type) ts.reduceRight(PairTypeTree)
        else if (ts.isEmpty) unitLiteral
        else ts.reduceRight(Pair(_, _))
      case WhileDo(cond, body) =>
        // { <label> def while$(): Unit = if (cond) { body; while$() } ; while$() }
        val call = Apply(Ident(nme.WHILE_PREFIX), Nil)
        val rhs = If(cond, Block(body, call), unitLiteral)
        labelDefAndCall(nme.WHILE_PREFIX, rhs, call)
      case DoWhile(body, cond) =>
        // { label def doWhile$(): Unit = { body; if (cond) doWhile$() } ; doWhile$() }
        val call = Apply(Ident(nme.DO_WHILE_PREFIX), Nil)
        val rhs = Block(body, If(cond, call, unitLiteral))
        labelDefAndCall(nme.DO_WHILE_PREFIX, rhs, call)
      case ForDo(enums, body) =>
        makeFor(nme.foreach, nme.foreach, enums, body) orElse tree
      case ForYield(enums, body) =>
        makeFor(nme.map, nme.flatMap, enums, body) orElse tree
      case PatDef(mods, pats, tpt, rhs) =>
        val pats1 = if (tpt.isEmpty) pats else pats map (Typed(_, tpt))
        Thicket.make(pats1 map (makePatDef(mods, _, rhs)))
      case _ =>
        tree
    }
    tree1 match {
      case tree1: NameTree => tree1.withName(tree1.name.encode)
      case _ => tree1
    }
  }.withPos(tree.pos)

 /** If tree is a variable pattern, return its name and type, otherwise return None.
   */
  private object VarPattern {
    def unapply(tree: Tree): Option[VarInfo] = tree match {
      case id: Ident => Some(id, TypeTree())
      case Typed(id: Ident, tpt) => Some((id, tpt))
      case _ => None
    }
  }

  /** Traverse pattern and collect all variable names with their types in buffer.
   *  Works for expanded as well as unexpanded patterns
   *
   */
  private object getVars extends TreeAccumulator[ListBuffer[VarInfo]] {
    override def apply(buf: ListBuffer[VarInfo], tree: Tree): ListBuffer[VarInfo] = {
      def seenName(name: Name) = buf exists (_._1.name == name)
      def add(named: NameTree, t: Tree): ListBuffer[VarInfo] =
        if (seenName(named.name)) buf else buf += ((named, t))
      tree match {
        case Bind(nme.WILDCARD, _) =>
          foldOver(buf, tree)
        case tree @ Bind(_, Typed(tree1, tpt)) if !mayBeTypePat(tpt) =>
          apply(add(tree, tpt), tree1)
        case tree @ Bind(_, tree1) =>
          apply(add(tree, TypeTree()), tree1)
        case Typed(id: Ident, t) if isVarPattern(id) =>
          add(id, t)
        case id: Ident if isVarPattern(id) =>
          add(id, TypeTree())
        case _ =>
          foldOver(buf, tree)
      }
    }
  }
}