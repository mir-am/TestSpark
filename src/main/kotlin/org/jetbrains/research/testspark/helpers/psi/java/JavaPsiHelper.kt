package org.jetbrains.research.testspark.helpers.psi.java

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.research.testspark.helpers.psi.Language
import org.jetbrains.research.testspark.helpers.psi.PsiClassWrapper
import org.jetbrains.research.testspark.helpers.psi.PsiHelper
import org.jetbrains.research.testspark.helpers.psi.PsiMethodWrapper
import org.jetbrains.research.testspark.tools.llm.SettingsArguments

class JavaPsiHelper(private val psiFile: PsiFile) : PsiHelper {

    override val language: Language get() = Language.Java

    private val log = Logger.getInstance(this::class.java)

    override fun generateMethodDescriptor(psiMethod: PsiMethodWrapper): String {
        val methodDescriptor = psiMethod.methodDescriptor
        log.info("Method description: $methodDescriptor")
        return methodDescriptor
    }

    override fun getSurroundingClass(caretOffset: Int): PsiClassWrapper? {
        val classElements = PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiClass::class.java)
        for (cls in classElements) {
            if (cls.containsOffset(caretOffset)) {
                val javaClassWrapper = JavaPsiClassWrapper(cls)
                if (javaClassWrapper.isTestableClass()) {
                    log.info("Surrounding class for caret in $caretOffset is ${javaClassWrapper.qualifiedName}")
                    return javaClassWrapper
                }
            }
        }
        log.info("No surrounding class for caret in $caretOffset")
        return null
    }

    override fun getSurroundingMethod(caretOffset: Int): PsiMethodWrapper? {
        val methodElements = PsiTreeUtil.findChildrenOfAnyType(psiFile, PsiMethod::class.java)
        for (method in methodElements) {
            if (method.body != null && method.containsOffset(caretOffset)) {
                val surroundingClass =
                    PsiTreeUtil.getParentOfType(method, PsiClass::class.java) ?: continue
                val surroundingClassWrapper = JavaPsiClassWrapper(surroundingClass)
                if (surroundingClassWrapper.isTestableClass()) {
                    val javaMethod = JavaPsiMethodWrapper(method)
                    log.info("Surrounding method for caret in $caretOffset is ${javaMethod.methodDescriptor}")
                    return javaMethod
                }
            }
        }
        log.info("No surrounding method for caret in $caretOffset")
        return null
    }

    override fun getSurroundingLine(caretOffset: Int): Int? {
        val doc = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: return null

        val selectedLine = doc.getLineNumber(caretOffset)
        val selectedLineText =
            doc.getText(TextRange(doc.getLineStartOffset(selectedLine), doc.getLineEndOffset(selectedLine)))

        if (selectedLineText.isBlank()) {
            log.info("Line $selectedLine at caret $caretOffset is blank")
            return null
        }
        log.info("Surrounding line at caret $caretOffset is $selectedLine")

        // increase by one is necessary due to different start of numbering
        return selectedLine + 1
    }

    override fun collectClassesToTest(
        project: Project,
        classesToTest: MutableList<PsiClassWrapper>,
        caretOffset: Int,
    ) {
        // check if cut has any none java super class
        val maxPolymorphismDepth = SettingsArguments(project).maxPolyDepth(0)

        val cutPsiClass = getSurroundingClass(caretOffset)!!
        var currentPsiClass = cutPsiClass
        for (index in 0 until maxPolymorphismDepth) {
            if (!classesToTest.contains(currentPsiClass)) {
                classesToTest.add(currentPsiClass)
            }

            if (currentPsiClass.superClass == null ||
                currentPsiClass.superClass!!.qualifiedName.startsWith("java.")
            ) {
                break
            }
            currentPsiClass = currentPsiClass.superClass!!
        }
        log.info("There are ${classesToTest.size} classes to test")
    }

    override fun getInterestingPsiClassesWithQualifiedNames(
        project: Project,
        classesToTest: List<PsiClassWrapper>,
        polyDepthReducing: Int,
    ): MutableSet<PsiClassWrapper> {
        val interestingPsiClasses: MutableSet<JavaPsiClassWrapper> = mutableSetOf()

        var currentLevelClasses =
            mutableListOf<PsiClassWrapper>().apply { addAll(classesToTest) }

        repeat(SettingsArguments(project).maxInputParamsDepth(polyDepthReducing)) {
            val tempListOfClasses = mutableSetOf<JavaPsiClassWrapper>()

            currentLevelClasses.forEach { classIt ->
                classIt.methods.forEach { methodIt ->
                    (methodIt as JavaPsiMethodWrapper).parameterList.parameters.forEach { paramIt ->
                        PsiTypesUtil.getPsiClass(paramIt.type)?.let { typeIt ->
                            JavaPsiClassWrapper(typeIt).let {
                                if (!it.qualifiedName.startsWith("java.")) {
                                    interestingPsiClasses.add(it)
                                }
                            }
                        }
                    }
                }
            }
            currentLevelClasses = mutableListOf<PsiClassWrapper>().apply { addAll(tempListOfClasses) }
            interestingPsiClasses.addAll(tempListOfClasses)
        }
        log.info("There are ${interestingPsiClasses.size} interesting psi classes")
        return interestingPsiClasses.toMutableSet()
    }

    override fun getInterestingPsiClassesWithQualifiedNames(
        cut: PsiClassWrapper,
        psiMethod: PsiMethodWrapper,
    ): MutableSet<PsiClassWrapper> {
        val interestingPsiClasses = cut.getInterestingPsiClassesWithQualifiedNames(psiMethod)
        log.info("There are ${interestingPsiClasses.size} interesting psi classes from method ${psiMethod.methodDescriptor}")
        return interestingPsiClasses
    }

    override fun getCurrentListOfCodeTypes(e: AnActionEvent): Array<*>? {
        val result: ArrayList<String> = arrayListOf()
        val caret: Caret =
            e.dataContext.getData(CommonDataKeys.CARET)?.caretModel?.primaryCaret ?: return result.toArray()

        val javaPsiClassWrapped = getSurroundingClass(caret.offset) as JavaPsiClassWrapper?
        val javaPsiMethodWrapped = getSurroundingMethod(caret.offset) as JavaPsiMethodWrapper?
        val line: Int? = getSurroundingLine(caret.offset)

        javaPsiClassWrapped?.let { result.add(getClassHTMLDisplayName(it)) }
        javaPsiMethodWrapped?.let { result.add(getMethodHTMLDisplayName(it)) }
        line?.let { result.add(getLineHTMLDisplayName(it)) }

        if (javaPsiClassWrapped != null && javaPsiMethodWrapped != null) {
            log.info(
                "The test can be generated for: \n " +
                    " 1) Class ${javaPsiClassWrapped.qualifiedName} \n" +
                    " 2) Method ${javaPsiMethodWrapped.name} \n" +
                    " 3) Line $line",
            )
        }

        return result.toArray()
    }

    override fun getLineHTMLDisplayName(line: Int) = "<html><b><font color='orange'>line</font> $line</b></html>"

    override fun getClassHTMLDisplayName(psiClass: PsiClassWrapper): String =
        "<html><b><font color='orange'>${psiClass.classType.representation}</font> ${psiClass.qualifiedName}</b></html>"

    override fun getMethodHTMLDisplayName(psiMethod: PsiMethodWrapper): String {
        return if ((psiMethod as JavaPsiMethodWrapper).isDefaultConstructor) {
            "<html><b><font color='orange'>default constructor</font></b></html>"
        } else if (psiMethod.isConstructor) {
            "<html><b><font color='orange'>constructor</font></b></html>"
        } else if (psiMethod.isMethodDefault) {
            "<html><b><font color='orange'>default method</font> ${psiMethod.name}</b></html>"
        } else {
            "<html><b><font color='orange'>method</font> ${psiMethod.name}</b></html>"
        }
    }

    private fun PsiElement.containsOffset(caretOffset: Int): Boolean {
        return (textRange.startOffset <= caretOffset) && (textRange.endOffset >= caretOffset)
    }
}
