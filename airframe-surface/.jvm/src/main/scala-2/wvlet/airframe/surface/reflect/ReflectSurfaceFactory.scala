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
package wvlet.airframe.surface.reflect

import wvlet.airframe.surface.TypeName.sanitizeTypeName

import java.lang.reflect.{Constructor, InvocationTargetException}
import java.util.concurrent.ConcurrentHashMap
import wvlet.airframe.surface.*
import wvlet.log.LogSupport

import scala.jdk.CollectionConverters.*
import scala.reflect.runtime.{universe => ru}

/**
  */
object ReflectSurfaceFactory extends LogSupport {
  import ru.*

  private type TypeName = String

  private[surface] val surfaceCache =
    new ConcurrentHashMap[TypeName, Surface].asScala
  private[surface] val methodSurfaceCache =
    new ConcurrentHashMap[TypeName, Seq[MethodSurface]].asScala
  private[surface] val typeMap = new ConcurrentHashMap[Surface, ru.Type].asScala

  private def belongsToScalaDefault(t: ru.Type) = {
    t match {
      case ru.TypeRef(prefix, _, _) =>
        val scalaDefaultPackages = Seq("scala.", "scala.Predef.", "scala.util.")
        scalaDefaultPackages.exists(p => prefix.dealias.typeSymbol.fullName.startsWith(p))
      case _ => false
    }
  }

  def of[A: ru.WeakTypeTag]: Surface = ofType(implicitly[ru.WeakTypeTag[A]].tpe)

  def ofType(tpe: ru.Type): Surface = {
    apply(tpe)
  }
  def ofClass(cls: Class[_]): Surface = {
    val cs  = mirror.classSymbol(cls)
    val tpe = cs.toType
    ofType(tpe) match {
      // Workaround for sbt's layered class loader, which cannot find the original classes using the reflect mirror
      case Alias(_, _, AnyRefSurface) if cs.isTrait =>
        new GenericSurface(cls)
      case other => other
    }
  }

  private def getPrimaryConstructorOf(cls: Class[_]): Option[Constructor[_]] = {
    val constructors = cls.getConstructors
    if (constructors.size == 0) {
      None
    } else {
      Some(constructors(0))
    }
  }

  private def getFirstParamTypeOfPrimaryConstructor(cls: Class[_]): Option[Class[_]] = {
    getPrimaryConstructorOf(cls).flatMap { constructor =>
      val constructorParamTypes = constructor.getParameterTypes
      if (constructorParamTypes.size == 0) {
        None
      } else {
        Some(constructorParamTypes(0))
      }
    }
  }

  def localSurfaceOf[A: ru.WeakTypeTag](context: Any): Surface = {
    val tpe = implicitly[ru.WeakTypeTag[A]].tpe
    ofType(tpe) match {
      case r: RuntimeGenericSurface =>
        getFirstParamTypeOfPrimaryConstructor(r.rawType) match {
          case Some(outerClass) if outerClass == context.getClass =>
            // Add outer context class to the Surface to support Surface.objectFactory -> newInstance(outer, p1, p2, ...)
            r.withOuter(context.asInstanceOf[AnyRef])
          case _ =>
            // In this surface, we cannot support objectFactory, but param etc. will work
            r
        }
      case other => other
    }
  }

  def findTypeOf(s: Surface): Option[ru.Type] = typeMap.get(s)

  def get(name: String): Surface = {
    surfaceCache.getOrElse(name, throw new NoSuchElementException(s"Surface ${name} is not found in cache"))
  }

  private def typeNameOf(t: ru.Type): String = {
    sanitizeTypeName(t.dealias.typeSymbol.fullName)
  }

  private def isTaggedType(t: ru.Type): Boolean = {
    typeNameOf(t).startsWith("wvlet.airframe.surface.tag.")
  }

  private[reflect] def fullTypeNameOf(tpe: ru.Type): TypeName = {
    val name = tpe match {
      case t if t.typeArgs.length == 2 && isTaggedType(t) =>
        s"${fullTypeNameOf(t.typeArgs(0))}@@${fullTypeNameOf(t.typeArgs(1))}"
      case alias @ TypeRef(prefix, symbol, args)
          if symbol.isType &&
            symbol.asType.isAliasType &&
            !belongsToScalaDefault(alias) =>
        val name     = symbol.asType.name.decodedName.toString
        val fullName = s"${prefix.typeSymbol.fullName}.${name}"
        fullName
      case ct: ru.ConstantType =>
        // Distinguish literal types (e.g., Int(1), Boolean(true)) from their primitive types (Int, Boolean)
        s"${ct.typeSymbol.fullName}(${ct.value.value})"
      case TypeRef(prefix, typeSymbol, args) if args.isEmpty =>
        typeSymbol.fullName
      case TypeRef(prefix, typeSymbol, args) if !args.isEmpty =>
        val typeArgs = args.map(fullTypeNameOf(_)).mkString(",")
        s"${typeSymbol.fullName}[${typeArgs}]"
      case _ => tpe.typeSymbol.fullName
    }
    sanitizeTypeName(name)
  }

  def apply(tpe: ru.Type): Surface = {
    val tpeName = fullTypeNameOf(tpe)
    if (!surfaceCache.contains(tpeName)) {
      val surface = new SurfaceFinder().surfaceOf(tpe)
      surfaceCache += tpeName -> surface
    }
    surfaceCache(fullTypeNameOf(tpe))
  }

  def methodsOf(s: Surface): Seq[MethodSurface] = {
    findTypeOf(s)
      .map { tpe =>
        methodsOfType(tpe)
      }
      .getOrElse(Seq.empty)
  }

  def methodsOf[A: ru.WeakTypeTag]: Seq[MethodSurface] =
    methodsOfType(implicitly[ru.WeakTypeTag[A]].tpe)

  def methodsOfType(tpe: ru.Type, cls: Option[Class[_]] = None): Seq[MethodSurface] = {
    val name = fullTypeNameOf(tpe)
    if (!methodSurfaceCache.contains(name)) {
      val methodSurface = new SurfaceFinder().createMethodSurfaceOf(tpe, cls)
      methodSurfaceCache += name -> methodSurface
    }
    methodSurfaceCache(name)
  }

  def methodsOfClass(cls: Class[_]): Seq[MethodSurface] = {
    val tpe = mirror.classSymbol(cls).toType
    methodsOfType(tpe, Some(cls))
  }

  private val rootMirror  = ru.runtimeMirror(this.getClass.getClassLoader)
  private val mirrorCache = new ConcurrentHashMap[ClassLoader, Mirror]().asScala

  private[surface] def mirror = {
    val cl = Thread.currentThread.getContextClassLoader
    val m  = mirrorCache.getOrElseUpdate(cl, ru.runtimeMirror(cl))
    m
  }

  private def resolveClass(tpe: ru.Type): Class[_] = {
    try {
      mirror.runtimeClass(tpe)
    } catch {
      case e: Throwable =>
        try {
          // Using the root mirror is necessary to resolve classes within sbt's class loader
          rootMirror.runtimeClass(tpe)
        } catch {
          case e: Throwable =>
            classOf[Any]
        }
    }
  }

  def hasAbstractMethods(t: ru.Type): Boolean =
    t.members.exists(x => x.isMethod && x.isAbstract && !x.isAbstractOverride)

  private def isAbstract(t: ru.Type): Boolean = {
    t.typeSymbol.isAbstract && hasAbstractMethods(t)
  }

  private type SurfaceMatcher = PartialFunction[ru.Type, Surface]

//  private def printMethod(x: ru.Symbol): Unit = {
//    val owner = x.owner
//    info(
//      s"${x}\t: public: ${x.isPublic}, abstract: ${x.isAbstract}, owner: ${owner}, owner.abstract ${owner.isAbstract}".stripMargin
//    )
//  }

  private class SurfaceFinder extends LogSupport {
    private val seen       = scala.collection.mutable.Set[ru.Type]()
    private val methodSeen = scala.collection.mutable.Set[ru.Type]()

    private def allMethodsOf(t: ru.Type): Iterable[MethodSymbol] = {
      // Sort the members in the source code order
      t.members.sorted
        .filter { x =>
          nonObject(x.owner) &&
          x.isMethod &&
          x.isPublic &&
          !x.isConstructor &&
          !x.isImplementationArtifact &&
          !x.isMacro &&
          !x.isImplicit &&
          // synthetic is used for functions returning default values of method arguments (e.g., ping$default$1)
          !x.isSynthetic
        }
        .map(_.asMethod)
        .filter { x =>
          val name = x.name.decodedName.toString
          !x.isAccessor &&
          !name.startsWith("$") &&
          name != "<init>"
        }
    }

    def localMethodsOf(t: ru.Type): Iterable[MethodSymbol] = {
      allMethodsOf(t)
        .filter { m =>
          isOwnedByTargetClass(m, t)
        }
    }

    private def nonObject(x: ru.Symbol): Boolean = {
      !x.isImplementationArtifact &&
      !x.isSynthetic &&
      // !x.isAbstract &&
      x.fullName != "scala.Any" &&
      x.fullName != "java.lang.Object"
    }

    private def isOwnedByTargetClass(m: MethodSymbol, t: ru.Type): Boolean = {
      m.owner == t.typeSymbol || t.baseClasses
        .filter(nonObject)
        .exists(_ == m.owner)
    }

    def createMethodSurfaceOf(targetType: ru.Type, cls: Option[Class[_]] = None): Seq[MethodSurface] = {
      val name = fullTypeNameOf(targetType)
      if (methodSurfaceCache.contains(name)) {
        methodSurfaceCache(name)
      } else if (methodSeen.contains(targetType)) {
        throw new IllegalArgumentException(s"recursive type in method: ${targetType.typeSymbol.fullName}")
      } else {
        methodSeen += targetType
        val methodSurfaces = {
          val localMethods = targetType match {
            case t @ TypeRef(prefix, typeSymbol, typeArgs) =>
              localMethodsOf(t.dealias).toSeq.distinct
            case t @ RefinedType(List(_, baseType), decls: MemberScope) =>
              (localMethodsOf(baseType) ++ localMethodsOf(t)).toSeq.distinct
            case _ =>
              Seq.empty
          }

          val lst = IndexedSeq.newBuilder[MethodSurface]
          for (m <- localMethods) {
            try {
              val mod   = modifierBitMaskOf(m)
              val owner = cls.map(ofClass(_)).getOrElse(surfaceOf(targetType))
              val name  = m.name.decodedName.toString
              val ret   = surfaceOf(m.returnType)
              val args  = methodParametersOf(targetType, m)
              lst += ReflectMethodSurface(mod, owner, name, ret, args.toIndexedSeq)
            } catch {
              case e: Throwable =>
                warn(s"Failed to create MethodSurface for ${m}", e)
            }
          }
          lst.result()
        }
        methodSurfaceCache += name -> methodSurfaces
        methodSurfaces
      }
    }

    def modifierBitMaskOf(m: MethodSymbol): Int = {
      var mod = 0
      if (m.isPublic) {
        mod |= MethodModifier.PUBLIC
      }
      if (m.isPrivate) {
        mod |= MethodModifier.PRIVATE
      }
      if (m.isProtected) {
        mod |= MethodModifier.PROTECTED
      }
      if (m.isStatic) {
        mod |= MethodModifier.STATIC
      }
      if (m.isFinal) {
        mod |= MethodModifier.FINAL
      }
      if (m.isAbstract) {
        mod |= MethodModifier.ABSTRACT
      }
      mod
    }

    def surfaceOf(tpe: ru.Type): Surface = {
      try {
        val fullName = fullTypeNameOf(tpe)
        if (surfaceCache.contains(fullName)) {
          surfaceCache(fullName)
        } else if (seen.contains(tpe)) {
          // Recursive type
          LazySurface(resolveClass(tpe), fullName)
        } else {
          seen += tpe
          val m = surfaceFactories.orElse[ru.Type, Surface] { case _ =>
            trace(f"Resolving the unknown type $tpe into AnyRef")
            new GenericSurface(resolveClass(tpe))
          }
          val surface: Surface =
            try {
              m(tpe)
            } catch {
              case e: NoSuchElementException =>
                // Failed to create surface (Not found in cache)
                AnyRefSurface
            }
          // Cache if not yet cached
          surfaceCache.getOrElseUpdate(fullName, surface)
          typeMap.getOrElseUpdate(surface, tpe)
          trace(s"surfaceOf(${tpe}) Surface: ${surface}, Surface class:${surface.getClass}, tpe: ${showRaw(tpe)}")
          surface
        }
      } catch {
        case e: Throwable =>
          error(s"Failed to build Surface.of[${tpe}]", e)
          throw e
      }
    }

    private val surfaceFactories: SurfaceMatcher =
      taggedTypeFactory orElse
        aliasFactory orElse
        higherKindedTypeFactory orElse
        primitiveTypeFactory orElse
        arrayFactory orElse
        optionFactory orElse
        tupleFactory orElse
        javaUtilFactory orElse
        javaEnumFactory orElse
        existentialTypeFactory orElse
        genericSurfaceWithConstructorFactory orElse
        genericSurfaceFactory

    private def primitiveTypeFactory: SurfaceMatcher = {
      case t if t =:= typeOf[String]               => Primitive.String
      case t if t =:= typeOf[Boolean]              => Primitive.Boolean
      case t if t =:= typeOf[Int]                  => Primitive.Int
      case t if t =:= typeOf[Long]                 => Primitive.Long
      case t if t =:= typeOf[Float]                => Primitive.Float
      case t if t =:= typeOf[Double]               => Primitive.Double
      case t if t =:= typeOf[Short]                => Primitive.Short
      case t if t =:= typeOf[Byte]                 => Primitive.Byte
      case t if t =:= typeOf[Char]                 => Primitive.Char
      case t if t =:= typeOf[Unit]                 => Primitive.Unit
      case t if t =:= typeOf[BigInt]               => Primitive.BigInt
      case t if t =:= typeOf[java.math.BigInteger] => Primitive.BigInteger
    }

    private def typeArgsOf(t: ru.Type): List[ru.Type] =
      t match {
        case TypeRef(prefix, symbol, args) =>
          args
        case ru.ExistentialType(quantified, underlying) =>
          typeArgsOf(underlying)
        case other =>
          List.empty
      }

    private def elementTypeOf(t: ru.Type): Surface = {
      typeArgsOf(t).map(surfaceOf(_)).head
    }

    private def higherKindedTypeFactory: SurfaceMatcher = {
      case t @ TypeRef(prefix, symbol, args) if t.typeArgs.isEmpty && t.takesTypeArgs =>
        // When higher-kinded types (e.g., Option[X], Future[X]) is passed as Option, Future without type arguments
        val inner    = surfaceOf(t.erasure)
        val name     = symbol.asType.name.decodedName.toString
        val fullName = s"${prefix.typeSymbol.fullName}.${name}"
        HigherKindedTypeSurface(name, fullName, inner, inner.typeArgs)
      case t @ TypeRef(NoPrefix, tpe, List()) if tpe.name.decodedName.toString.contains("$") =>
        wvlet.airframe.surface.ExistentialType
      case t @ TypeRef(NoPrefix, tpe, args) if !t.typeSymbol.isClass =>
        val name = tpe.name.decodedName.toString
        val ref: Surface = if (t.typeSymbol.isAbstract && t.typeArgs.isEmpty) {
          // When t is just a type letter (e.g., A, T, etc.)
          AnyRefSurface
        } else {
          surfaceOf(t.erasure)
        }
        HigherKindedTypeSurface(name, name, ref, args.map(ta => surfaceOf(ta)))
    }

    private def taggedTypeFactory: SurfaceMatcher = {
      case t if t.typeArgs.length == 2 && typeNameOf(t).startsWith("wvlet.airframe.surface.tag.") =>
        TaggedSurface(surfaceOf(t.typeArgs(0)), surfaceOf(t.typeArgs(1)))
    }

    private def aliasFactory: SurfaceMatcher = {
      case alias @ TypeRef(prefix, symbol, args)
          if symbol.isType &&
            symbol.asType.isAliasType &&
            !belongsToScalaDefault(alias) =>
        val dealiased = alias.dealias
        val inner = if (alias != dealiased) {
          surfaceOf(dealiased)
        } else {
          // When higher kind types are aliased (e.g., type M[A] = Future[A]),
          // alias.dealias will not return the aliased type (Future[A]),
          // So we need to find the resulting type by applying type erasure.
          surfaceOf(alias.erasure)
        }

        val name     = symbol.asType.name.decodedName.toString
        val fullName = s"${prefix.typeSymbol.fullName}.${name}"
        val a        = Alias(name, fullName, inner)
        a
    }

    private def arrayFactory: SurfaceMatcher = {
      case t if typeNameOf(t) == "scala.Array" =>
        ArraySurface(resolveClass(t), elementTypeOf(t))
    }

    private def optionFactory: SurfaceMatcher = {
      case t if typeNameOf(t) == "scala.Option" =>
        OptionSurface(resolveClass(t), elementTypeOf(t))
    }

    private def tupleFactory: SurfaceMatcher = {
      case t if t <:< typeOf[Product] && t.typeSymbol.fullName.startsWith("scala.Tuple") =>
        val paramType = typeArgsOf(t).map(x => surfaceOf(x))
        TupleSurface(resolveClass(t), paramType.toIndexedSeq)
    }

    private def javaUtilFactory: SurfaceMatcher = {
      case t
          if t =:= typeOf[java.io.File] ||
            t =:= typeOf[java.util.Date] ||
            t =:= typeOf[java.time.temporal.Temporal] ||
            t =:= typeOf[Throwable] ||
            t =:= typeOf[Exception] ||
            t =:= typeOf[Error] =>
        new GenericSurface(resolveClass(t))
    }

    private def isEnum(t: ru.Type): Boolean = {
      t.baseClasses.exists { x =>
        if (x.isJava && x.isType) {
          x.asType.fullName.toString.startsWith("java.lang.Enum")
        } else {
          false
        }
      }
    }

    private def javaEnumFactory: SurfaceMatcher = {
      case t if isEnum(t) =>
        JavaEnumSurface(resolveClass(t))
    }

    def hasAbstractMethods(t: ru.Type): Boolean =
      t.members.exists(x => x.isMethod && x.isAbstract && !x.isAbstractOverride)

    private def isAbstract(t: ru.Type): Boolean = {
      t.typeSymbol.isAbstract && hasAbstractMethods(t)
    }

    private def isPhantomConstructor(constructor: Symbol): Boolean =
      constructor.asMethod.fullName.endsWith("$init$")

    def publicConstructorsOf(t: ru.Type): Iterable[MethodSymbol] = {
      t.members
        .filter(m => m.isMethod && m.asMethod.isConstructor && m.isPublic)
        .filterNot(isPhantomConstructor)
        .map(
          _.asMethod
        )
    }

    def findPrimaryConstructorOf(t: ru.Type): Option[MethodSymbol] = {
      publicConstructorsOf(t).find(x => x.isPrimaryConstructor)
    }

    case class MethodArg(paramName: Symbol, tpe: ru.Type) {
      def name: String         = paramName.name.decodedName.toString
      def typeSurface: Surface = surfaceOf(tpe)
    }

    private def findMethod(m: ru.Type, name: String): Option[MethodSymbol] = {
      m.member(ru.TermName(name)) match {
        case ru.NoSymbol => None
        case other       => Some(other.asMethod)
      }
    }

    private def methodArgsOf(targetType: ru.Type, constructor: MethodSymbol): List[List[MethodArg]] = {
      val classTypeParams = if (targetType.typeSymbol.isClass) {
        targetType.typeSymbol.asClass.typeParams
      } else {
        List.empty[Symbol]
      }

      for (params <- constructor.paramLists) yield {
        // Necessary for resolving type parameters e.g., Cons[A](p:Cons[A]) => Cons[String](p:Cons[String])
        val concreteArgTypes = params.map { p =>
          try {
            p.typeSignature.substituteTypes(classTypeParams, targetType.typeArgs)
          } catch {
            case e: Throwable =>
              p.typeSignature
          }
        }
        var index = 1
        for ((p, t) <- params.zip(concreteArgTypes)) yield {
          index += 1
          MethodArg(p, t)
        }
      }
    }

    def methodParametersOf(targetType: ru.Type, method: MethodSymbol): Seq[RuntimeMethodParameter] = {
      val args = methodArgsOf(targetType, method).flatten
      val argTypes = args.map { (x: MethodArg) =>
        resolveClass(x.tpe)
      }.toSeq
      val ref = MethodRef(resolveClass(targetType), method.name.decodedName.toString, argTypes, method.isConstructor)

      var index = 0
      val surfaceParams = args.map { arg =>
        val t = arg.name
        // accessor = { x : Any => x.asInstanceOf[${target.tpe}].${arg.paramName} }
        val expr = RuntimeMethodParameter(
          method = ref,
          index = index,
          name = arg.name,
          surface = arg.typeSurface
        )
        index += 1
        expr
      }
      // Using IndexedSeq is necessary for Serialization
      surfaceParams.toIndexedSeq
    }

    private def existentialTypeFactory: SurfaceMatcher = { case t @ ru.ExistentialType(quantified, underlying) =>
      surfaceOf(underlying)
    }

    private def genericSurfaceWithConstructorFactory: SurfaceMatcher =
      new SurfaceMatcher with LogSupport {
        override def isDefinedAt(t: ru.Type): Boolean = {
          !isAbstract(t) && findPrimaryConstructorOf(t).exists(!_.paramLists.isEmpty)
        }
        override def apply(t: ru.Type): Surface = {
          val primaryConstructor = findPrimaryConstructorOf(t).get
          val typeArgs           = typeArgsOf(t).map(surfaceOf(_)).toIndexedSeq
          val methodParams       = methodParametersOf(t, primaryConstructor)

          val s = new RuntimeGenericSurface(
            resolveClass(t),
            typeArgs,
            params = methodParams,
            isStatic = t.typeSymbol.isStatic
          )
          s
        }
      }

    private def hasStringUnapply(t: ru.Type): Boolean = {
      t.companion match {
        case companion: Type =>
          // Find unapply(String): Option[X]
          companion.member(TermName("unapply")) match {
            case s: Symbol if s.isMethod && s.asMethod.paramLists.size == 1 =>
              val m    = s.asMethod
              val args = m.paramLists.head
              args.size == 1 &&
              args.head.typeSignature =:= typeOf[String] &&
              m.returnType <:< weakTypeOf[Option[_]] &&
              m.returnType.typeArgs.size == 1 &&
              m.returnType.typeArgs.head =:= t
            case _ => false
          }
        case _ => false
      }
    }

    private def genericSurfaceFactory: SurfaceMatcher = {
      case t @ TypeRef(prefix, symbol, args) if !args.isEmpty =>
        val typeArgs = typeArgsOf(t).map(surfaceOf(_)).toIndexedSeq
        new GenericSurface(resolveClass(t), typeArgs = typeArgs)
      case t @ TypeRef(NoPrefix, symbol, args) if !t.typeSymbol.isClass =>
        wvlet.airframe.surface.ExistentialType
      case t @ TypeRef(prefix, symbol, args) if resolveClass(t) == classOf[AnyRef] && !(t =:= typeOf[AnyRef]) =>
        // For example, trait MyTag, which has no implementation will be just an java.lang.Object
        val name     = t.typeSymbol.name.decodedName.toString
        val fullName = s"${prefix.typeSymbol.fullName}.${name}"
        Alias(name, fullName, AnyRefSurface)
      case t @ RefinedType(List(_, baseType), decl) =>
        // For traits with extended methods
        new GenericSurface(resolveClass(baseType))
      case t if hasStringUnapply(t) =>
        // Surface that can be constructed with unapply(String)
        EnumSurface(
          resolveClass(t),
          { (cl: Class[_], s: String) =>
            TypeConverter.convertToCls(s, cl)
          }
        )
      case t =>
        new GenericSurface(resolveClass(t))
    }
  }
}
