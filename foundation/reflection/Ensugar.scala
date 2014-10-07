package org.scalameta.reflection

import scala.tools.nsc.Global
import scala.language.experimental.macros
import scala.reflect.macros.whitebox.Context
import scala.org.scalameta.reflection.Helpers
import org.scalameta.unreachable
import org.scalameta.invariants._

// NOTE: this file undoes the desugarings applied by Scala's parser and typechecker to the extent possible with scala.reflect trees
// for instance, insertions of implicit argument lists are undone, because it's a simple Apply => Tree transformation
// however, we can't undo ClassDef, anonymous new or while-loop desugarings, because scala.reflect lacks the trees to represent those in their original form
// some of the undesugarings can be done automatically, some of them require https://github.com/scalameta/scalahost/blob/master/plugin/typechecker/Analyzer.scala
trait Ensugar {
  self: GlobalToolkit =>

  import global._
  import definitions._
  val currentRun = global.currentRun
  import currentRun.runDefinitions._
  import treeInfo._
  import scala.reflect.internal.Flags._

  import org.scalameta.invariants.Implication
  private def require[T](x: T): Unit = macro org.scalameta.invariants.Macros.require

  object Desugared {
    private val trivialSignature = new EnsugarSignature[Tree, Tree]{}
    def unapply(tree: Tree): Some[Tree] = Some(ensugar(tree)(trivialSignature))
    def unapply(trees: List[Tree]): Some[List[Tree]] = Some(trees.map(tree => ensugar(tree)(trivialSignature)))
  }

  def ensugar[Input <: Tree, Output <: Tree](tree: Input)(implicit ev: EnsugarSignature[Input, Output]): Output = {
    def loop(tree: Tree): Tree = {
      object transformer extends Transformer {
        override def transform(tree: Tree): Tree = {
          def logFailure() = {
            def summary(x: Any) = x match { case x: Product => x.productPrefix; case null => "null"; case _ => x.getClass }
            var details = tree.toString.replace("\n", "")
            if (details.length > 60) details = details.take(60) + "..."
            println("(" + summary(tree) + ") " + details)
          }
          try {
            object Desugared {
              def unapply(tree: Tree): Option[Tree] = tree match {
                case DesugaringProtocol(original) => Some(original)
                case MacroExpansion(expandee) => Some(expandee)
                case TypeTreeWithOriginal(original) => Some(original)
                case RefTreeWithOriginal(original) => Some(original)
                case TemplateWithOriginal(original) => Some(original)
                case SuperWithOriginal(original) => Some(original)
                case ClassOfWithOriginal(original) => Some(original)
                case SelfWithOriginal(original) => Some(original)
                case MemberDefWithTrimmableSynthetic(original) => Some(original)
                case MemberDefWithInferredReturnType(original) => Some(original)
                case MemberDefWithAnnotations(original) => Some(original)
                case MacroDef(original) => Some(original)
                case DefaultGetterDef(original) => Some(original)
                case LazyLocal(original) => Some(original)
                case LazyDef(original) => Some(original)
                case ImplicitConversion(original) => Some(original)
                case TypeApplicationWithInferredTypeArguments(original) => Some(original)
                case ApplicationWithArrayInstantiation(original) => Some(original)
                case ApplicationWithInferredImplicitArguments(original) => Some(original)
                case ApplicationWithInsertedApply(original) => Some(original)
                case ApplicationWithNamesOrDefaults(original) => Some(original)
                case StandalonePartialFunction(original) => Some(original)
                case LambdaPartialFunction(original) => Some(original)
                case CaseClassExtractor(original) => Some(original)
                case ClassTagExtractor(original) => Some(original)
                case VanillaExtractor(original) => Some(original)
                case AnnotatedTerm(original) => Some(original)
                case EtaExpansion(original) => Some(original)
                case InlinedConstant(original) => Some(original)
                case InsertedUnit(original) => Some(original)
                case _ => None
              }
            }
            tree match {
              case Desugared(original) => transform(original.appendScratchpad(tree))
              case _ => super.transform(tree)
            }
          } catch {
            case err: _root_.java.lang.AssertionError => logFailure(); throw err
            case err: _root_.org.scalameta.UnreachableError.type => logFailure(); throw err
            case ex: _root_.scala.Exception => logFailure(); throw ex
          }
        }

        // NOTE: this is the newly established desugaring protocol
        // if a transformer wants to be friendly to us, they can use this protocol to simplify our lives
        object DesugaringProtocol {
          def unapply(tree: Tree): Option[Tree] = tree.metadata.get("original").map(_.asInstanceOf[Tree].duplicate)
        }

        // TODO: remember macro expansions here, because the host will need to convert and attach them to expandee's attrs
        // TODO: current approaches to macro expansion metadata is a bit weird
        // because both expandees and expansions are attached with the same piece of metadata
        // we need to debug recursive macro expansions to see how they behave in the current system
        object MacroExpansion {
          def unapply(tree: Tree): Option[Tree] = {
            def postprocess(original: Tree): Tree = {
              // NOTE: this method is partially copy/pasted from Reshape.scala in scalac
              def mkImplicitly(tp: Type) = gen.mkNullaryCall(Predef_implicitly, List(tp)).setType(tp)
              val sym = original.symbol
              original match {
                // this hack is necessary until I fix implicit macros
                // so far tag materialization is implemented by sneaky macros hidden in scala-compiler.jar
                // hence we cannot reify references to them, because noone will be able to see them later
                // when implicit macros are fixed, these sneaky macros will move to corresponding companion objects
                // of, say, ClassTag or TypeTag
                case Apply(TypeApply(_, List(tt)), _) if sym == materializeClassTag            => mkImplicitly(appliedType(ClassTagClass, tt.tpe))
                case Apply(TypeApply(_, List(tt)), List(pre)) if sym == materializeWeakTypeTag => mkImplicitly(typeRef(pre.tpe, WeakTypeTagClass, List(tt.tpe)))
                case Apply(TypeApply(_, List(tt)), List(pre)) if sym == materializeTypeTag     => mkImplicitly(typeRef(pre.tpe, TypeTagClass, List(tt.tpe)))
                case _                                                                         => original
              }
            }
            def strip(tree: Tree): Tree = {
              duplicateAndKeepPositions(tree).removeMetadata("expandeeTree").removeAttachment[analyzer.MacroExpansionAttachment]
            }
            // NOTE: the expandeeTree metadata is attached by scala.meta macro expansion
            // the MacroExpansionAttachment attachment is attached by scala.reflect macro expansion
            (tree.metadata.get("expandeeTree").map(_.asInstanceOf[Tree]), tree.attachments.get[analyzer.MacroExpansionAttachment]) match {
              case (Some(original), _) => Some(strip(postprocess(original.duplicate)))
              case (None, Some(analyzer.MacroExpansionAttachment(original, _))) => Some(strip(postprocess(original.duplicate)))
              case _ => None
            }
          }
        }

        // TODO: infer which of the TypeBoundsTree bounds were specified explicitly by the user
        // TODO: in the future, when we'll have moved the validating part of refchecks before the macro expansion phase,
        // there won't be any necessity to support TypeTreeWithDeferredRefCheck trees
        object TypeTreeWithOriginal {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ TypeTree() if tree.original == null =>
              // NOTE: would be nice to disallow TypeTree(tpe) without originals, but allow TypeTree()
              // because that's what one can write syntactically
              // however we have scala.reflect macros, which can generate TypeTree(tpe) trees
              // so for the sake of compatibility we have to remain conservative
              // TODO: however, you know, let's ban synthetic TypeTrees for now and see where it leads
              require(tree.tpe == null)
              None
            case tree @ TypeTree() if tree.original != null =>
              val original = tree.original match {
                case original @ SingletonTypeTree(_) =>
                  treeCopy.SingletonTypeTree(original, original.metadata("originalRef").asInstanceOf[Tree].duplicate)
                case original @ CompoundTypeTree(templ) =>
                  // NOTE: this attachment is only going to work past typer
                  // but since we're not yet going to implement whitebox macros, that's not yet a problem
                  require(templ.self == noSelfType)
                  val Some(CompoundTypeTreeOriginalAttachment(parents1, stats1)) = templ.attachments.get[CompoundTypeTreeOriginalAttachment]
                  val templ1 = treeCopy.Template(templ, parents1.map(_.duplicate), noSelfType, stats1.map(_.duplicate)).setType(NoType).setSymbol(NoSymbol)
                  treeCopy.CompoundTypeTree(original, templ1)
                case original @ TypeBoundsTree(lo, hi) =>
                  val lo1 = if (lo.tpe =:= NothingTpe) EmptyTree else lo
                  val hi1 = if (hi.tpe =:= AnyTpe) EmptyTree else hi
                  treeCopy.TypeBoundsTree(original, lo1, hi1)
                case original @ AppliedTypeTree(fn, args) =>
                  treeCopy.AppliedTypeTree(original, fn, args)
                case original @ Annotated(_, arg) =>
                  // NOTE: annot from the original is unattributed, so we can't use it
                  // NOTE: if we have nested Annotated nodes, only the outermost one has annotations with attributed originals
                  def loop(tree: Tree, annots: List[Tree]): Tree = (tree, annots) match {
                    case (tree @ Annotated(_, arg), annot :: rest) => treeCopy.Annotated(tree, annot, loop(arg, rest))
                    case (tree, Nil) => tree
                    case _ => unreachable
                  }
                  val annots = tree.tpe.asInstanceOf[AnnotatedType].annotations.map(_.original)
                  require(annots.forall(_.nonEmpty))
                  loop(original, annots)
                case original @ ExistentialTypeTree(_, _) =>
                  // NOTE: original.tpt is partially screwed up and original.whereClauses is untyped
                  // therefore we look into the attachment, which has both tpt and whereClauses in their post-typecheck state
                  val ExistentialTypeTree(TypeTreeWithOriginal(tpt), whereClauses) = original.metadata("typedExistentialTypeTree").asInstanceOf[Tree].duplicate
                  treeCopy.ExistentialTypeTree(original, tpt, whereClauses)
                case original =>
                  original
              }
              Some(original.setType(tree.tpe))
            case in @ TypeTreeWithDeferredRefCheck() =>
              // NOTE: I guess, we can do deferred checks here as the converter isn't supposed to run in the middle of typer
              // we will have to revisit this in case we decide to support whitebox macros in Palladium
              unapply(in.check())
            case _ =>
              None
          }
        }

        // TODO: test the situation when tree.symbol is a package object
        object RefTreeWithOriginal {
          def unapply(tree: Tree): Option[Tree] = {
            object OriginalIdent { def unapply(tree: Tree): Option[Ident] = tree.metadata.get("originalIdent").map(_.asInstanceOf[Ident].duplicate) }
            object OriginalSelect { def unapply(tree: Tree): Option[Name] = tree.metadata.get("originalName").map(_.asInstanceOf[Name]) }
            (tree, tree) match {
              // Ident => This is a very uncommon situation, which happens when we typecheck a self reference
              // unfortunately, this self reference can't have a symbol, because self doesn't have a symbol, so we have to do some encoding
              case (This(_), OriginalIdent(orig)) => Some(orig.copyAttrs(tree).removeMetadata("originalIdent"))
              case (Select(_, _), OriginalIdent(orig)) => Some(orig.copyAttrs(tree).removeMetadata("originalIdent"))
              case (Select(qual, _), OriginalSelect(orig)) => Some(treeCopy.Select(tree, qual, orig).removeMetadata("originalName"))
              case (SelectFromTypeTree(qual, _), OriginalSelect(orig)) => Some(treeCopy.SelectFromTypeTree(tree, qual, orig).removeMetadata("originalName"))
              case _ => None
            }
          }
        }

        object TemplateWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalParents").map(_.asInstanceOf[List[Tree]].map(_.duplicate))) match {
            case (tree @ Template(_, self, body), Some(original)) => Some(treeCopy.Template(tree, original, self, body).removeMetadata("originalParents"))
            case _ => None
          }
        }

        object SuperWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalThis").map(_.asInstanceOf[This].duplicate)) match {
            case (tree @ Super(qual, mix), Some(originalThis)) => Some(treeCopy.Super(tree, originalThis, mix).removeMetadata("originalThis"))
            case _ => None
          }
        }

        object ClassOfWithOriginal {
          def unapply(tree: Tree): Option[Tree] = (tree, tree.metadata.get("originalClassOf").map(_.asInstanceOf[Tree].duplicate)) match {
            case (tree @ Literal(Constant(tpe: Type)), Some(original)) => Some(original.setType(tree.tpe))
            case _ => None
          }
        }

        object SelfWithOriginal {
          def unapply(tree: Tree): Option[Tree] = tree.metadata.get("originalSelf").map(_.asInstanceOf[Tree].duplicate.removeMetadata("originalSelf"))
        }

        private def isInferred(tree: Tree): Boolean = tree match {
          case tt @ TypeTree() => tt.nonEmpty && tt.original == null
          case _ => false
        }

        object MemberDefWithTrimmableSynthetic {
          def unapply(tree: Tree): Option[Tree] = {
            implicit class RichTrees(trees: List[Tree]) {
              private def importantName(mdef: MemberDef): Boolean = mdef.name.startsWith("ev$")
              private def needsTrimming(mdef: MemberDef): Boolean = mdef.mods.isSynthetic && !mdef.mods.isArtifact && !importantName(mdef)
              private def needsTrimming(tree: Tree): Boolean = tree match { case mdef: MemberDef => needsTrimming(mdef); case _ => false }
              def needTrimming = trees.exists(needsTrimming)
              def trim = trees.filter(tree => !needsTrimming(tree))
            }
            tree match {
              case tree @ PackageDef(pid, stats) if stats.needTrimming => Some(treeCopy.PackageDef(tree, pid, stats.trim))
              case tree @ Block(stats, expr) if stats.needTrimming => Some(treeCopy.Block(tree, stats.trim, expr))
              case tree @ Template(parents, self, stats) if stats.needTrimming => Some(treeCopy.Template(tree, parents, self, stats.trim))
              case _ => None
            }
          }
        }

        // TODO: wasEmpty is not really working well here and checking nullness of originals is too optimistic
        // however, the former produces much uglier results, so I'm going for the latter
        object MemberDefWithInferredReturnType {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ ValDef(_, _, tt @ TypeTree(), _) if isInferred(tt) => Some(copyValDef(tree)(tpt = EmptyTree))
            case tree @ DefDef(_, _, _, _, tt @ TypeTree(), _) if isInferred(tt) => Some(copyDefDef(tree)(tpt = EmptyTree))
            case _ => None
          }
        }

        // TODO: support classfile annotation args (ann.original.argss are untyped at the moment)
        object MemberDefWithAnnotations {
          def unapply(tree: Tree): Option[Tree] = {
            def isSyntheticAnnotation(ann: AnnotationInfo): Boolean = ann.atp.typeSymbol.fullName == "scala.reflect.macros.internal.macroImpl"
            def isDesugaredMods(mdef: MemberDef): Boolean = mdef.mods.annotations.isEmpty && tree.symbol.annotations.filterNot(isSyntheticAnnotation).nonEmpty
            def ensugarMods(mdef: MemberDef): Modifiers = mdef.mods.withAnnotations(mdef.symbol.annotations.flatMap(ensugarAnnotation))
            def ensugarAnnotation(ann: AnnotationInfo): Option[Tree] = ann.original match {
              case original if original.nonEmpty => Some(original)
              case EmptyTree if isSyntheticAnnotation(ann) => None
              case EmptyTree => unreachable
            }
            tree match {
              // case tree @ PackageDef(_, _) => // package defs don't have annotations
              case tree: TypeDef if isDesugaredMods(tree) => Some(copyTypeDef(tree)(mods = ensugarMods(tree)))
              case tree: ClassDef if isDesugaredMods(tree) => Some(copyClassDef(tree)(mods = ensugarMods(tree)))
              case tree: ModuleDef if isDesugaredMods(tree) => Some(copyModuleDef(tree)(mods = ensugarMods(tree)))
              case tree: ValDef if isDesugaredMods(tree) => Some(copyValDef(tree)(mods = ensugarMods(tree)))
              case tree: DefDef if isDesugaredMods(tree) => Some(copyDefDef(tree)(mods = ensugarMods(tree)))
              case _ => None
            }
          }
        }

        object MacroDef {
          def unapply(tree: Tree): Option[Tree] = {
            tree match {
              case tree: DefDef if !tree.hasMetadata("originalMacro") =>
                def macroSigs(tree: Tree) = tree match {
                  case tree: DefDef => tree.symbol.annotations.filter(_.tree.tpe.typeSymbol.fullName == "scala.reflect.macros.internal.macroImpl")
                  case _ => Nil
                }
                def parseMacroSig(sig: AnnotationInfo) = {
                  val q"new $_[..$_]($_(..$args)[..$targs])" = sig.tree
                  val metadata = args.collect{
                    case Assign(Literal(Constant(s: String)), Literal(Constant(v))) => s -> v
                    case Assign(Literal(Constant(s: String)), tree) => s -> loop(tree)
                  }.toMap
                  metadata + ("targs" -> targs.map(loop))
                }
                val originalBody = macroSigs(tree) match {
                  case legacySig :: palladiumSig :: Nil =>
                    Some(parseMacroSig(palladiumSig)("implDdef").asInstanceOf[DefDef].rhs)
                  case legacySig :: Nil =>
                    // TODO: obtain the impl ref exactly how it was written by the programmer
                    val legacy = parseMacroSig(legacySig)
                    val className = legacy("className").asInstanceOf[String]
                    val methodName = legacy("methodName").asInstanceOf[String]
                    val isBundle = legacy("isBundle").asInstanceOf[Boolean]
                    val targs = legacy("targs").asInstanceOf[List[Tree]]
                    require(className.endsWith("$") ==> !isBundle)
                    val containerSym = if (isBundle) rootMirror.staticClass(className) else rootMirror.staticModule(className.stripSuffix("$"))
                    val container = Ident(containerSym).setType(if (isBundle) containerSym.asType.toType else containerSym.info)
                    val methodSym = containerSym.info.member(TermName(methodName))
                    var implRef: Tree = Select(container, methodSym).setType(methodSym.info)
                    if (targs.nonEmpty) implRef = TypeApply(implRef, targs).setType(appliedType(methodSym.info, targs.map(_.tpe)))
                    Some(implRef)
                  case _ :: _ =>
                    unreachable
                  case _ =>
                    None
                }
                originalBody.map(originalBody => copyDefDef(tree)(rhs = originalBody).appendMetadata("originalMacro" -> tree))
              case _ => None
            }
          }
        }

        object DefaultGetterDef {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree: DefDef if tree.symbol.isDefaultGetter => Some(EmptyTree)
            case _ => None
          }
        }

        object LazyLocal {
          def unapply(tree: Tree): Option[Tree] = tree match {
            // NOTE: can't use tree.name here, because tree.symbol.name != tree.name for lazy synthetics
            // NOTE: can't use tree.symbol.name here, because sometimes underlying fields aren't mangled
            case tree: ValDef if tree.symbol.isLazy && tree.symbol.isMutable => Some(EmptyTree)
            case _ => None
          }
        }

        object LazyDef {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ DefDef(mods, name, Nil, Nil, tpt, Block(List(Assign(lzy1: RefTree, rhs)), lzy2: RefTree))
            if tree.symbol.isLazy && lzy1.name == lzy2.name && lzy1.symbol.isLazy && lzy1.symbol.isMutable =>
              Some(ValDef(mods, name, tpt, rhs).copyAttrs(tree))
            case _ =>
              None
          }
        }

        object ImplicitConversion {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case ApplyImplicitView(_, arg) => Some(arg)
            case _ => None
          }
        }

        // TODO: test how this works with new
        object TypeApplicationWithInferredTypeArguments {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case TypeApply(fn, targs) if targs.exists(isInferred) => Some(fn)
            case _ => None
          }
        }

        object ApplicationWithArrayInstantiation {
          def unapply(tree: Tree): Option[Tree] = tree.metadata.get("originalArray").map(_.asInstanceOf[Tree].duplicate)
        }

        // TODO: test how this works with new
        object ApplicationWithInferredImplicitArguments {
          def unapply(tree: Tree): Option[Tree] = (tree, dissectApplied(tree)) match {
            case (ApplyToImplicitArgs(fn, _), _) => Some(fn)
            case (Apply(fn, args), Applied(Select(This(_), nme.CONSTRUCTOR), _, _)) if isImplicitMethodType(fn.tpe) => Some(fn)
            case _ => None
          }
        }

        object ApplicationWithInsertedApply {
          // TODO: make this work, putting a workaround in place for now
          // def unapply(tree: Tree): Option[Tree] = tree.metadata.get("originalApplee").map(_.asInstanceOf[Tree])
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Select(qual, _) if tree.symbol.name == nme.apply && tree.symbol.paramss.nonEmpty => Some(qual)
            case _ => None
          }
        }

        object ApplicationWithNamesOrDefaults {
          def unapply(tree: Tree): Option[Tree] = {
            object OriginalApply {
              def unapply(tree: Tree) = tree.metadata.get("originalApply").map(_.asInstanceOf[Apply].duplicate)
            }
            def isNameDefaultQual(tree: Tree) = tree match {
              case ValDef(_, name, _, _) => name.startsWith(nme.QUAL_PREFIX)
              case _ => false
            }
            def isNameDefaultTemp(tree: Tree) = tree match {
              case vdef: ValDef => vdef.symbol.isArtifact
              case _ => false
            }
            def refsNameDefaultTemp(arg: Tree, vdef: ValDef) = {
              val isTrivialRef = arg.symbol == vdef.symbol
              val isWildcardStarRef = WildcardStarArg.unapply(arg).map(_.symbol == vdef.symbol).getOrElse(false)
              val isByNameRef = arg match { case Apply(Select(arg, nme.apply), List()) => arg.symbol == vdef.symbol; case _ => false }
              isTrivialRef || isWildcardStarRef || isByNameRef
            }
            def subNameDefaultTemp(arg: Tree, vdef: ValDef) = {
              val result = new TreeSubstituter(List(vdef.symbol), List(vdef.rhs)).transform(arg)
              result match { case Apply(Select(Function(List(), body), nme.apply), List()) => body; case _ => result }
            }
            val (qualsym, qual, vdefs0, app @ Applied(_, _, argss)) = tree match {
              case Block((qualdef @ ValDef(_, _, _, qual)) +: vdefs, app) if isNameDefaultQual(qualdef) => (qualdef.symbol, qual, vdefs, app)
              case Block(vdefs, app) if vdefs.forall(isNameDefaultTemp) => (NoSymbol, EmptyTree, vdefs, app)
              case tree => (NoSymbol, EmptyTree, Nil, tree)
            }
            val vdefs = vdefs0.map{ case vdef: ValDef => vdef }
            def hasNamesDefaults(args: List[Tree]) = {
              args.exists(arg => isDefaultGetter(arg) || vdefs.exists(vdef => refsNameDefaultTemp(arg, vdef)))
            }
            def undoNamesDefaults(args: List[Tree], depth: Int) = {
              case class Arg(tree: Tree, ipos: Int, inamed: Int) { val param = app.symbol.paramss(depth)(ipos) }
              val indexed = args.map(arg => arg -> vdefs.indexWhere(refsNameDefaultTemp(arg, _))).zipWithIndex.flatMap({
                /*    default    */ case ((arg, _), _) if isDefaultGetter(arg) => None
                /*   positional  */ case ((arg, -1), ipos) => Some(Arg(arg, ipos, -1))
                /* default+named */ case ((_, inamed), _) if isDefaultGetter(vdefs(inamed).rhs) => None
                /*     named     */ case ((arg, inamed), ipos) => Some(Arg(subNameDefaultTemp(arg, vdefs(inamed)), ipos, inamed))
              })
              if (indexed.forall(_.inamed == -1)) indexed.map(_.tree)
              else indexed.sortBy(_.inamed).map(arg => AssignOrNamedArg(Ident(arg.param).setType(arg.tree.tpe), arg.tree))
            }
            def loop(tree: Tree, depth: Int): Tree = tree match {
              case Apply(fun, args) if hasNamesDefaults(args) => treeCopy.Apply(tree, loop(fun, depth - 1), undoNamesDefaults(args, depth))
              case Apply(fun, args) => treeCopy.Apply(tree, loop(fun, depth - 1), args)
              case TypeApply(core, targs) => treeCopy.TypeApply(tree, loop(core, depth - 1), targs)
              case Select(core, name) if qualsym != NoSymbol && core.symbol == qualsym => treeCopy.Select(tree, qual, name).removeMetadata("originalQual")
              case core => core
            }
            val sugaredDoesntLookLikeNamesDefaults = qualsym == NoSymbol && !hasNamesDefaults(argss.flatten)
            val originalDoesntLookLikeNamesDefaults = tree match { case OriginalApply(Applied(_, _, argss)) => argss.flatten.forall(!_.isInstanceOf[AssignOrNamedArg]); case _ => true }
            val doesntLookLikeNamesDefaults = sugaredDoesntLookLikeNamesDefaults && originalDoesntLookLikeNamesDefaults
            if (app.symbol == null || app.symbol == NoSymbol || app.exists(_.isErroneous) || doesntLookLikeNamesDefaults || OriginalApply.unapply(tree).isEmpty) None
            else {
              // NOTE: necessary to smooth out the rough edges of the translation
              // 1) if all arguments are positional, the typer will drop names completely,
              // which is something that undoNamesDefaults can't do anything about
              // e.g. `def foo(x: Int = 2) = ???; foo(x = something)`
              // 2) conversely, if there were no named arguments, but there were defaults
              // undoNamesDefaults can sometimes be confused into thinking that there were named arguments
              // e.g. `def foo(x: Int = 2, y: Int = 3) = ???; var unstable = this; unstable.foo(2)`
              def correlate(tree1: Tree, tree0: Tree): Tree = {
                def lookupParam(name: Name): Symbol = app.symbol.info.paramss.flatten.find(_.name.toString == name.toString).get
                def correlateArgs(args1: List[Tree], args0: List[Tree]): List[Tree] = {
                  require(args1.length == args0.length)
                  args1.zip(args0).map({
                    case (arg1 @ AssignOrNamedArg(_, _), arg0 @ AssignOrNamedArg(_, _)) => arg1
                    case (arg1 @ AssignOrNamedArg(_, rhs), arg0) => rhs
                    case (arg1, arg0 @ AssignOrNamedArg(Ident(name), _)) => AssignOrNamedArg(Ident(lookupParam(name)).setType(arg1.tpe), arg1)
                    case (arg1, arg0) => arg1
                  })
                }
                (tree1, tree0) match {
                  case (tree1 @ Apply(fn1, args1), Apply(fn0, args0)) => treeCopy.Apply(tree1, correlate(fn1, fn0), correlateArgs(args1, args0))
                  case _ => tree1
                }
              }
              val result = loop(app, depth = argss.length - 1)
              val original = tree.metadata("originalApply").asInstanceOf[Apply].duplicate
              Some(correlate(result, original).removeMetadata("originalApply"))
            }
          }
        }

        object StandalonePartialFunction {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Typed(Block((gcdef @ ClassDef(_, tpnme.ANON_FUN_NAME, _, _)) :: Nil, q"new ${Ident(tpnme.ANON_FUN_NAME)}()"), tpt)
            if tpt.tpe.typeSymbol == definitions.PartialFunctionClass =>
              val (m, cases) :: Nil = gcdef.impl.body.collect { case DefDef(_, nme.applyOrElse, _, _, _, m @ Match(_, cases :+ _)) => (m, cases) }
              Some(treeCopy.Match(m, EmptyTree, cases))
            case _ =>
              None
          }
        }

        object LambdaPartialFunction {
          object SyntheticParamDefs {
            def unapply(trees: List[ValDef]): Option[Int] = {
              val yes = trees.zipWithIndex.forall{ case (ValDef(mods, name, _, _), i) => mods.isSynthetic && name.startsWith("x" + i + "$") }
              if (yes) Some(trees.length) else None
            }
          }
          object SyntheticParamRef {
            def unapply(tree: Tree): Option[Int] = tree match {
              case Literal(Constant(())) =>
                Some(0)
              case Ident(name) if name.toString.startsWith("x0$") =>
                Some(1)
              case Apply(_, args) if TupleClass.seq.contains(tree.symbol.owner.companion) =>
                val yes = args.zipWithIndex.forall{ case (Ident(name), i) => name.startsWith("x" + i + "$") }
                if (yes) Some(args.length) else None
              case _ =>
                None
            }
          }
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Function(SyntheticParamDefs(arity1), Match(SyntheticParamRef(arity2), cases)) if arity1 == arity2 =>
              Some(Match(EmptyTree, cases).setType(tree.tpe))
            case _ =>
              None
          }
        }

        object CaseClassExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case tree @ Apply(tpt @ TypeTree(), args) if tpt.tpe.isInstanceOf[MethodType] =>
              // TypeTree[1]().setOriginal(Select[2](Ident[3](scala#26), scala.Tuple2#1688))
              // [1] MethodType(List(TermName("_1")#30490, TermName("_2")#30491), TypeRef(ThisType(scala#27), scala.Tuple2#1687, List(TypeRef(SingleType(SingleType(NoPrefix, TermName("c")#15795), TermName("universe")#15857), TypeName("TermSymbol")#9456, List()), TypeRef(SingleType(SingleType(NoPrefix, TermName("c")#15795), TermName("universe")#15857), TypeName("Ident")#10233, List()))))
              // [2] SingleType(SingleType(ThisType(<root>#2), scala#26), scala.Tuple2#1688)
              // [3] SingleType(ThisType(<root>#2), scala#26)
              require(tpt.original != null)
              Some(treeCopy.Apply(tree, tpt.original, args).appendScratchpad(tpt.tpe))
            case _ =>
              None
          }
        }

        // TODO: figure out whether the classtag-style extractor was written explicitly by the programmer
        object ClassTagExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case outerPat @ UnApply(q"$ref.$unapply[..$targs](..$_)", List(innerPat))
            if outerPat.fun.symbol.owner == definitions.ClassTagClass && unapply == nme.unapply =>
              Some(innerPat)
            case _ =>
              None
          }
        }

        // TODO: test case class unapplication with targs
        // TODO: also test case objects
        // TODO: figure out whether targs were explicitly specified or not
        object VanillaExtractor {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case UnApply(fn @ q"$_.$unapply[..$_](..$_)", args) =>
              require(unapply == nme.unapply || unapply == nme.unapplySeq)
              Some(Apply(dissectApplied(fn).callee, args).setType(tree.tpe))
            case _ =>
              None
          }
        }

        // NOTE: partially copy/pasted from Reshape.scala from scalac
        object AnnotatedTerm {
          object TypeTreeWithRawOriginal {
            def unapply(tree: TypeTree): Option[Tree] = tree match {
              case tt @ TypeTree() if tt.original != null => Some(tt.original)
              case _ => None
            }
          }
          object AnnotatedWithAnns {
            def unapply(tree: Annotated): Option[(List[Tree], Tree)] = tree match {
              case Annotated(ann, annotated: Annotated) => unapply(annotated).map({ case (anns, arg) => (anns :+ ann, arg) })
              case Annotated(ann, arg) => Some((List(ann), arg))
              case _ => None
            }
          }
          def unapply(tree: Tree): Option[Tree] = tree match {
            case ty @ Typed(_, tt @ TypeTreeWithRawOriginal(AnnotatedWithAnns(_, arg))) if arg.isTerm =>
              // NOTE: annot from original is unattributed, so we can't use it
              val original @ Annotated(_, arg) = tt.original
              val List(annot, _*) = tree.tpe.asInstanceOf[AnnotatedType].annotations.map(_.original)
              Some(treeCopy.Annotated(original, annot, arg))
            case _ =>
              None
          }
        }

        object EtaExpansion {
          def unapply(tree: Tree): Option[Tree] = {
            val manualEta = tree.metadata.get("originalManualEta").map(_.asInstanceOf[Tree])
            val autoEta = tree.metadata.get("originalAutoEta").map(_.asInstanceOf[Tree])
            (manualEta, autoEta) match {
              case (Some(original), _) => Some(Typed(original, Function(Nil, EmptyTree)).setType(tree.tpe))
              case (_, Some(original)) => Some(original)
              case _ => None
            }
          }
        }

        object InlinedConstant {
          def unapply(tree: Tree): Option[Tree] = tree.metadata.get("originalConstant").map(_.asInstanceOf[Tree].duplicate.removeMetadata("originalConstant"))
        }

        object InsertedUnit {
          def unapply(tree: Tree): Option[Tree] = tree match {
            case Block(List(expr), unit @ Literal(Constant(()))) if unit.hasMetadata("insertedUnit") => Some(expr)
            case _ => None
          }
        }
      }
      object advancedDuplicator extends Transformer {
        override def transform(tree: Tree): Tree = tree match {
          case tt: TypeTree if tt.original != null => tt.duplicate.setOriginal(advancedDuplicator.transform(tt.original.duplicate))
          case _ => super.transform(tree.duplicate)
        }
      }
      val duplicatedInput = advancedDuplicator.transform(tree)
      val preliminaryResult = transformer.transform(duplicatedInput)
      collapseEmptyTrees.transform(preliminaryResult)
    }
    loop(tree).asInstanceOf[Output]
  }
}

trait EnsugarSignature[Input, Output]
object EnsugarSignature {
  // NOTE: can't be bothered with contravariant implicits, so writing a macro instead
  implicit def materialize[Input, Output]: EnsugarSignature[Input, Output] = macro impl[Input]
  def impl[Input: c.WeakTypeTag](c: Context) = {
    import c.universe._
    import c.internal._
    val input = c.weakTypeOf[Input]
    val output = input match {
      case TypeRef(pre, sym, args) => typeRef(pre, {
        val explicitMapping = Map(
          "TermTree" -> "Tree", // macro expansion
          "SymTree!" -> "Tree", // macro expansion
          "NameTree!" -> "Tree", // macro expansion
          "RefTree!" -> "Tree", // macro expansion
          "Select" -> "Tree", // macro expansion
          "Ident" -> "Tree", // macro expansion
          "UnApply" -> "GenericApply", // extractor-based unapplication
          "AssignOrNamedArg" -> "AssignOrNamedArg", // macros can't expand into these
          "Super" -> "Super", // macros can't expand into these
          "TypeTree" -> "Tree" // not all originals are TypTrees, some are just Trees
                               // the rest of the tree types are mapped to themselves (see below)
        )
        implicit class StringlyTyped(x1: String) {
          def toType: Type = typeRef(pre, pre.member(TypeName(x1.stripSuffix("!"))), Nil)
          def toSymbol: Symbol = pre.member(TypeName(x1.stripSuffix("!")))
        }
        val matches = explicitMapping.keys.filter(k => if (k.endsWith("!")) input =:= k.toType else input <:< k.toType).toList
        val disambiguated = matches.filter(m1 => matches.forall(m2 => !(m1 != m2 && (m2.toType <:< m1.toType))))
        disambiguated match {
          case m :: Nil => explicitMapping(m).toSymbol
          case Nil => sym
          case other => c.abort(c.enclosingPosition, s"$input => ?: derivation failed, because of unexpected match list $other")
        }
      }, args)
      case tpe => tpe
    }
    q"new _root_.org.scalameta.reflection.EnsugarSignature[$input, $output] {}"
  }
}