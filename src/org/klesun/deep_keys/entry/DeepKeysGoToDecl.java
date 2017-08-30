package org.klesun.deep_keys.entry;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocParamTag;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocTag;
import com.jetbrains.php.lang.psi.elements.ArrayIndex;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.impl.ArrayAccessExpressionImpl;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import org.jetbrains.annotations.Nullable;
import org.klesun.deep_keys.DeepTypeResolver;
import org.klesun.deep_keys.helpers.FuncCtx;
import org.klesun.deep_keys.helpers.IFuncCtx;
import org.klesun.deep_keys.helpers.SearchContext;
import org.klesun.deep_keys.resolvers.var_res.DocParamRes;
import org.klesun.lang.Lang;
import org.klesun.lang.Tls;

import java.util.*;

/**
 * go to declaration functionality for associative array keys
 */
public class DeepKeysGoToDecl extends Lang implements GotoDeclarationHandler
{
    // just treating a symptom. i dunno why duplicates appear - they should not
    private static void removeDuplicates(L<PsiElement> psiTargets)
    {
        Set<PsiElement> fingerprints = new HashSet<>();
        int size = psiTargets.size();
        for (int k = size - 1; k >= 0; --k) {
            if (fingerprints.contains(psiTargets.get(k))) {
                psiTargets.remove(k);
            }
            fingerprints.add(psiTargets.get(k));
        }
    }

    @Nullable
    @Override
    public PsiElement[] getGotoDeclarationTargets(PsiElement psiElement, int i, Editor editor)
    {
        SearchContext search = new SearchContext().setDepth(35);
        IFuncCtx funcCtx = new FuncCtx(search, L());

        L<PsiElement> psiTargets = L();
        opt(psiElement.getParent())
            .fap(toCast(StringLiteralExpressionImpl.class))
            .thn(literal -> Lang.opt(literal.getParent())
                .fap(Lang.toCast(ArrayIndex.class))
                .map(index -> index.getParent())
                .fap(Lang.toCast(ArrayAccessExpressionImpl.class))
                .map(expr -> expr.getValue())
                .fap(toCast(PhpExpression.class))
                .map(srcExpr -> funcCtx.findExprType(srcExpr).types)
                .thn(arrayTypes -> arrayTypes.forEach(arrayType -> {
                    String key = literal.getContents();
                    if (arrayType.keys.containsKey(key)) {
                        psiTargets.add(arrayType.keys.get(key).definition);
                    }
                })))
            .els(() -> opt(psiElement)
                .fap(v -> Tls.findParent(v, PhpDocParamTag.class, psi -> true))
                .fap(tag -> new DocParamRes(funcCtx).resolve(tag))
                .map(mt -> mt.types)
                .thn(types -> types.forEach(t -> psiTargets.add(t.definition))));

        removeDuplicates(psiTargets);

        return psiTargets.toArray(new PsiElement[psiTargets.size()]);
    }

    @Nullable
    @Override
    public String getActionText(DataContext dataContext) {
        // dunno what this does
        return null;
    }
}
