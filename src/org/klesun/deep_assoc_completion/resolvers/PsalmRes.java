package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocTag;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.klesun.deep_assoc_completion.contexts.IExprCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.structures.Build;
import org.klesun.deep_assoc_completion.structures.DeepType;
import org.klesun.deep_assoc_completion.structures.Key;
import org.klesun.deep_assoc_completion.structures.KeyType;
import org.klesun.deep_assoc_completion.structures.psalm.*;
import org.klesun.lang.It;
import org.klesun.lang.L;
import org.klesun.lang.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.klesun.lang.Lang.*;

public class PsalmRes {
    final private IExprCtx ctx;

    public PsalmRes(IExprCtx ctx)
    {
        this.ctx = ctx;
    }

    //====================================================
    // following functions retrieve resulting type from signatures and resolved generics
    //====================================================

    private static boolean isArrayLike(TClass cls)
    {
        // see https://psalm.dev/docs/templated_annotations/#builtin-templated-classes-and-interfaces
        return cls.fqn.equals("array") || cls.fqn.equals("\\array")
            || cls.fqn.equals("iterable") || cls.fqn.equals("\\iterable")
            || cls.fqn.equals("Traversable") || cls.fqn.equals("\\Traversable")
            || cls.fqn.equals("ArrayAccess") || cls.fqn.equals("\\ArrayAccess")
            || cls.fqn.equals("IteratorAggregate") || cls.fqn.equals("\\IteratorAggregate")
            || cls.fqn.equals("Iterator") || cls.fqn.equals("\\Iterator")
            || cls.fqn.equals("SeekableIterator") || cls.fqn.equals("\\SeekableIterator")
            || cls.fqn.equals("Generator") || cls.fqn.equals("\\Generator")
            || cls.fqn.equals("ArrayObject") || cls.fqn.equals("\\ArrayObject")
            || cls.fqn.equals("ArrayIterator") || cls.fqn.equals("\\ArrayIterator")
            || cls.fqn.equals("SplDoublyLinkedList") || cls.fqn.equals("\\SplDoublyLinkedList")
            // these two probably should not be allowed to have 2 generics...
            || cls.fqn.equals("DOMNodeList") || cls.fqn.equals("\\DOMNodeList")
            || cls.fqn.equals("SplQueue") || cls.fqn.equals("\\SplQueue");
    }

    private static IIt<Key> genericsToArrKeys(List<IType> defs, PsiElement goToPsi, Map<String, MemIt<DeepType>> generics)
    {
        if (defs.size() == 1) {
            KeyType keyt = KeyType.integer(goToPsi);
            Key keyObj = new Key(keyt, goToPsi);
            keyObj.addType(() -> {
                IIt<DeepType> rit = psalmToDeep(defs.get(0), goToPsi, generics);
                return Mt.reuse(rit);
            });
            return som(keyObj);
        } else if (defs.size() == 2) {
            IIt<DeepType> kit = psalmToDeep(defs.get(1), goToPsi, generics);
            KeyType keyt = KeyType.mt(kit, goToPsi);
            Key keyObj = new Key(keyt, goToPsi);
            keyObj.addType(() -> {
                IIt<DeepType> rit = psalmToDeep(defs.get(1), goToPsi, generics);
                return Mt.reuse(rit);
            });
            return som(keyObj);
        } else {
            return non();
        }
    }

    private static IResolvedIt<DeepType> psalmPlainClsToDeep(TClass cls, PsiElement goToPsi, Map<String, MemIt<DeepType>> generics)
    {
        PhpType phpType = new PhpType().add(cls.fqn);
        L<Mt> genMts = Lang.It(cls.generics)
            .map(psalm -> psalmToDeep(psalm, goToPsi, generics))
            .map(Mt::mem)
            .arr();
        IIt<Key> keyEntries = non();
        if (isArrayLike(cls)) {
            keyEntries = genericsToArrKeys(cls.generics, goToPsi, generics);
        }
        DeepType type = new Build(goToPsi, phpType)
            .isExactPsi(false).generics(genMts)
            .keys(keyEntries).get();
        return som(type);
    }

    private static IIt<DeepType> psalmToDeep(IType psalmType, PsiElement goToPsi, Map<String, MemIt<DeepType>> generics)
    {
        Opt<MemIt<DeepType>> asGeneric = Tls.cast(TClass.class, psalmType)
            .fop(cls -> opt(generics.get(cls.fqn)));
        if (asGeneric.has()) {
            // the only type that requires heavy deep resolution, for all others
            // can reuse the collection and safely take type hints to show
            return asGeneric.fap(a -> a);
        }

        return IResolvedIt.fst(L::non
            , () -> Tls.cast(TAssoc.class, psalmType)
                .map(assoc -> {
                    It<Key> keyEntries = It(assoc.keys.entrySet()).map(e -> {
                        String keyName = e.getKey();
                        IType psalmVal = e.getValue();
                        Mt valMt = Mt.reuse(psalmToDeep(psalmVal, goToPsi, generics));
                        PhpType ideaType = valMt.getIdeaTypes().fst().def(PhpType.UNSET);
                        List<String> comments = opt(assoc.keyToComments.get(keyName))
                            .fap(c -> c).flt(c -> !c.trim().equals("")).map(c -> c).arr();
                        return new Key(keyName, goToPsi)
                            .addType(Granted(valMt), ideaType)
                            .addComments(comments);
                    });
                    return new Build(goToPsi, PhpType.ARRAY)
                        .isExactPsi(false)
                        .keys(keyEntries)
                        .get();
                })
            , () -> Tls.cast(TFunc.class, psalmType)
                .map(func -> new Build(goToPsi, PhpType.CALLABLE)
                    .isExactPsi(false)
                    .returnTypeGetters(list(ctx -> {
                        return psalmToDeep(func.returnType, goToPsi, generics).mem();
                    }))
                    .get())
            , () -> Tls.cast(TClass.class, psalmType)
                .map(cls -> psalmPlainClsToDeep(cls, goToPsi, generics))
                .def(L())
            // if we ever support references, should add check for infinite recursion here...
            , () -> Tls.cast(TMulti.class, psalmType)
                .fap(multi -> It(multi.types)
                    .fap(t -> psalmToDeep(t, goToPsi, generics)))
                .arr()
            , () -> Tls.cast(TPrimitive.class, psalmType)
                .map(prim -> new DeepType(goToPsi, prim.kind, prim.stringValue))
        );
    }

    //====================================================
    // following functions retrieve generic type from signatures and context
    //====================================================

    private static It<DeepType> getGenericTypeFromArg(IType psalmt, Mt deept, String generic, PsiElement psi, IExprCtx emptyCtx)
    {
        return It.cnc(
            non()
            , Tls.cast(TClass.class, psalmt).fap(cls -> {
                PhpType phpType = new PhpType().add(cls.fqn);
                if (cls.fqn.equals(generic)) {
                    return deept.types;
                } else if (isArrayLike(cls)) {
                    if (cls.generics.size() == 1) {
                        Mt elMt = deept.getEl();
                        IType elPsalmt = cls.generics.get(0);
                        return getGenericTypeFromArg(elPsalmt, elMt, generic, psi, emptyCtx);
                    } else if (cls.generics.size() == 2) {
                        Mt keyMt = deept.types.fap(t -> t.keys).fap(k -> k.keyType.types).wap(Mt::mem);
                        IType keyPsalmt = cls.generics.get(1);
                        It<DeepType> genKeyTit = getGenericTypeFromArg(keyPsalmt, keyMt, generic, psi, emptyCtx);

                        Mt valMt = deept.getEl();
                        IType valPsalmt = cls.generics.get(1);
                        It<DeepType> getValTit = getGenericTypeFromArg(valPsalmt, valMt, generic, psi, emptyCtx);

                        return It.cnc(genKeyTit, getValTit);
                    }
                } else if (list("callable", "\\Closure", "function").contains(cls.fqn)) {
                    // callable<Targ1, Targ2, Tret>
                    return L(cls.generics).lst().fap(retPsalmt -> {
                        Mt retmt = deept.types.fap(t -> t.getReturnTypes(emptyCtx)).wap(Mt::mem);
                        return getGenericTypeFromArg(retPsalmt, retmt, generic, psi, emptyCtx);
                    });
                } else {
                    // TODO: support keyed arrays, fields, function argument types
                }
                DeepType asParamCls = new DeepType(psi, phpType, false);
                asParamCls.generics = It(cls.generics)
                    .map(psalmgt -> deept.types
                        .fap(t -> t.generics)
                        .fap(gmt -> getGenericTypeFromArg(psalmgt, gmt, generic, psi, emptyCtx))
                        .wap(tit -> Mt.mem(tit)))
                    .arr();
                return It(som(asParamCls));
            })
        );
    }

    public static It<DeepType> getGenTypeFromFunc(PsalmFuncInfo.GenericDef g, L<PsalmFuncInfo.ArgDef> params, IExprCtx ctx, PsiElement psi)
    {
        return params.fap(p -> p.order.fap(o -> ctx.func().getArg(o))
            .fap(mt -> p.psalmType
                .fap(psalmt -> getGenericTypeFromArg(
                    psalmt, mt, g.name, psi, ctx.subCtxEmpty()
                ))));
    }

    private static Map<String, MemIt<DeepType>> getClsGenericTypes(L<PsalmFuncInfo.GenericDef> classGenerics, IExprCtx ctx)
    {
        Map<String, MemIt<DeepType>> result = new HashMap<>();
        classGenerics.fch((g, i) -> result.put(g.name, ctx.getThisType()
            .fap(t -> t.generics.gat(i))
            .fap(mt -> mt.types).mem()));
        return result;
    }

    private static Map<String, MemIt<DeepType>> getGenericTypes(PsalmFuncInfo psalmInfo, IExprCtx ctx)
    {
        Map<String, MemIt<DeepType>> result = new HashMap<>();
        psalmInfo.funcGenerics.fch(g -> {
            MemIt<DeepType> mit = getGenTypeFromFunc(
                g, psalmInfo.params, ctx, psalmInfo.psi
            ).mem();
            result.put(g.name, mit);
        });
        result.putAll(getClsGenericTypes(psalmInfo.classGenerics, ctx));
        // not a real generic, but close enough
        result.put("static", ctx.getSelfType().map(pst -> new DeepType(psalmInfo.psi, pst)).itr().mem());
        return result;
    }

    //====================================================
    // following functions are entry points
    //====================================================

    private static IIt<DeepType> infoToDeep(PsalmFuncInfo psalmInfo, PsiElement goToPsi, IExprCtx ctx)
    {
        return psalmInfo.returnType.rap(psalmt -> {
            Map<String, MemIt<DeepType>> gents = getGenericTypes(psalmInfo, ctx);
            return psalmToDeep(psalmt, goToPsi, gents);
        });
    }

    public static IIt<DeepType> resolveReturn(PhpDocTag docTag, IExprCtx ctx)
    {
        return opt(docTag.getParent())
            .cst(PhpDocComment.class)
            .map(docComment -> PsalmFuncInfo.parse(docComment))
            .rap(psalmInfo -> infoToDeep(psalmInfo, docTag, ctx));
    }

    public static IIt<DeepType> resolveMagicReturn(PhpDocComment docComment, String methName, IExprCtx ctx)
    {
        PsalmFuncInfo.PsalmClsInfo clsInfo = PsalmFuncInfo.parseClsDoc(docComment);
        return opt(clsInfo.magicMethods.get(methName))
            .rap(psalmInfo -> infoToDeep(psalmInfo, docComment, ctx));
    }

    public static IIt<DeepType> resolveVar(PhpDocComment docComment, String varName, IExprCtx ctx)
    {
        PsalmFuncInfo psalmInfo = PsalmFuncInfo.parse(docComment);
        Map<String, MemIt<DeepType>> generics = getGenericTypes(psalmInfo, ctx);
        return psalmInfo.params.flt(p -> p.name.equals(varName) || p.name.equals(""))
            .fop(p -> p.psalmType).arr()
            .rap(psalmt -> psalmToDeep(psalmt, docComment, generics));
    }

    public static IIt<DeepType> resolveMagicProp(PhpDocComment docComment, String name, IExprCtx ctx)
    {
        PsalmFuncInfo.PsalmClsInfo clsInfo = PsalmFuncInfo.parseClsDoc(docComment);
        Map<String, MemIt<DeepType>> generics = getClsGenericTypes(clsInfo.generics, ctx);
        return opt(clsInfo.magicProps.get(name))
            .map(p -> p.psalmType)
            .rap(psalmt -> psalmToDeep(psalmt, docComment, generics));
    }
}
