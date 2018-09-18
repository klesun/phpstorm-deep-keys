package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.*;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.klesun.deep_assoc_completion.*;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.resolvers.var_res.AssRes;
import org.klesun.deep_assoc_completion.resolvers.var_res.DocParamRes;
import org.klesun.lang.*;

import java.util.Set;

public class FieldRes extends Lang
{
    final private FuncCtx ctx;

    public FieldRes(FuncCtx ctx)
    {
        this.ctx = ctx;
    }

    private static It<FieldReferenceImpl> findReferences(PsiFile file, String name)
    {
        // ReferenceSearch seems to cause freezes
//        SearchScope scope = GlobalSearchScope.fileScope(
//            fieldRef.getProject(),
//            decl.getContainingFile().getVirtualFile()
//        );
//        return ReferencesSearch.search(decl, scope, false).findAll();

        return It(PsiTreeUtil.findChildrenOfType(file, FieldReferenceImpl.class))
            .flt(ref -> name.equals(ref.getName()));
    }

    private static boolean areInSameScope(PsiElement a, PsiElement b)
    {
        Opt<Function> aFunc = Tls.findParent(a, Function.class, v -> true);
        Opt<Function> bFunc = Tls.findParent(b, Function.class, v -> true);
        return aFunc.equals(bFunc);
    }

    // when you do instanceof, IDEA type acquires the class, so  it may
    // happen that args passed to an instance as instance of one class
    // could be used in some other instanceof-ed class if we don't do this check
    private static boolean isSameClass(FuncCtx ctx, PhpClass fieldCls)
    {
        // return true if ctx class is empty or ctx class constructor is in fieldCls
        // (if fieldCls is ctx class or ctx class inherits constructor from fieldCls)
        Set<String> ctxFqns = ArrCtorRes.ideaTypeToFqn(ctx.clsIdeaType.def(PhpType.UNSET));
        return ctxFqns.isEmpty() || ctxFqns.contains(fieldCls.getFQN());
    }

    public It<DeepType> resolve(FieldReferenceImpl fieldRef)
    {
        S<Mt> getObjMt = Tls.onDemand(() -> opt(fieldRef.getClassReference())
            .fop(ref -> Opt.fst(
                () -> ctx.clsIdeaType
                    .flt(typ -> ref.getText().equals("static"))
                    .flt(typ -> ArrCtorRes.resolveIdeaTypeCls(typ, ref.getProject()).has())
                    .map(typ -> new Mt(list(new DeepType(ref, typ)))),
                () -> opt(ctx.findExprType(ref).wap(Mt::new))
            ))
            .def(Mt.INVALID_PSI));

        L<Field> declarations = L.fst(
            () -> opt(fieldRef)
                .flt(ref -> !ref.getText().startsWith("static::")) // IDEA is bad at static:: resolution
                .fap(ref -> L(ref.multiResolve(false)))
                .map(res -> res.getElement())
                .fop(toCast(Field.class)).arr(),
            () -> opt(getObjMt.get())
                .fap(mt -> ArrCtorRes.resolveMtCls(mt, fieldRef.getProject()))
                .fap(cls -> L(cls.getFields()))
                .flt(f -> f.getName().equals(fieldRef.getName())).arr()
        );
        It<DeepType> propDocTs = It(list());
        if (declarations.size() == 0) {
            propDocTs = getObjMt.get().getProps()
                .flt(prop -> prop.name.equals(fieldRef.getName()))
                .fap(prop -> prop.getTypes());
        }
        It<DeepType> declTypes = declarations.itr()
            .fap(resolved -> {
                FuncCtx implCtx = new FuncCtx(ctx.getSearch());
                It<DeepType> defTs = Tls.cast(FieldImpl.class, resolved).itr()
                    .map(fld -> fld.getDefaultValue())
                    .fop(toCast(PhpExpression.class))
                    .fap(def -> implCtx.findExprType(def));

                It<DeepType> docTs = opt(resolved.getContainingClass()).itr()
                    .fop(cls -> opt(cls.getDocComment()))
                    .fap(doc -> L(doc.getPropertyTags()))
                    .flt(tag -> opt(tag.getProperty()).flt(pro -> pro.getName().equals(fieldRef.getName())).has())
                    .fap(tag -> new DocParamRes(ctx).resolve(tag));

                It<Assign> asses = opt(resolved.getContainingFile()).itr()
                    .fap(file -> findReferences(file, fieldRef.getName()))
                    .fap(assPsi -> Tls.findParent(assPsi, Method.class, a -> true)
                        .flt(meth -> meth.getName().equals("__construct"))
                        .map(meth -> fieldRef.getClassReference())
                        .fop(toCast(PhpExpression.class))
                        .fap(ref -> ctx.findExprType(ref))
                        .fop(t -> t.ctorArgs)
                        .flt(ctx -> opt(resolved.getContainingClass())
                            .map(cls -> isSameClass(ctx, cls)).def(true))
                        .wap(ctxs -> It.cnc(
                            ctxs,
                            Tls.ifi(areInSameScope(fieldRef, assPsi), () -> list(ctx)),
                            Tls.ifi(!ctxs.has(), () -> list(implCtx))
                        ))
                        .fop(methCtx -> (new AssRes(methCtx)).collectAssignment(assPsi, false)));

                return It.cnc(
                    defTs, docTs,
                    AssRes.assignmentsToTypes(asses)
                );
            });

        return It.cnc(propDocTs, declTypes);
    }
}
