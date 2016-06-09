package ru.yole.jitwatch

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.containers.isNullOrEmpty
import org.adoptopenjdk.jitwatch.core.HotSpotLogParser
import org.adoptopenjdk.jitwatch.core.IJITListener
import org.adoptopenjdk.jitwatch.core.ILogParseErrorListener
import org.adoptopenjdk.jitwatch.core.JITWatchConfig
import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.IReadOnlyJITDataModel
import org.adoptopenjdk.jitwatch.model.JITEvent
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.*
import java.io.File
import javax.swing.SwingUtilities

class JitWatchModelService(private val project: Project) {
    private val config = JITWatchConfig()
    private var _model: IReadOnlyJITDataModel? = null
    private val bytecodeAnnotations = mutableMapOf<VirtualFile, Map<IMetaMember, BytecodeAnnotations>>()

    val model: IReadOnlyJITDataModel?
        get() = _model

    fun loadLog(logFile: VirtualFile) {
        val jitListener = object : IJITListener {
            override fun handleLogEntry(entry: String?) {
            }

            override fun handleErrorEntry(entry: String?) {
            }

            override fun handleReadComplete() {
            }

            override fun handleJITEvent(event: JITEvent?) {
            }

            override fun handleReadStart() {
            }
        }

        val parseErrors = mutableListOf<Pair<String, String>>()
        val errorListener = ILogParseErrorListener { title, body -> parseErrors.add(title to body) }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading compilation log", false) {
            override fun run(indicator: ProgressIndicator) {
                val parser = HotSpotLogParser(jitListener)
                parser.config = config
                parser.processLogFile(File(logFile.canonicalPath), errorListener)
                _model = parser.model

                SwingUtilities.invokeLater { modelUpdated() }
            }
        })
    }

    private fun modelUpdated() {
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    fun getMetaClass(cls: PsiElement?): MetaClass? {
        if (cls == null) return null
        return model?.let {
            val languageSupport = LanguageSupport.forLanguage(cls.language) ?: return null
            val classQName = languageSupport.getClassVMName(cls)
            it.packageManager.getMetaClass(classQName)
        }
    }

    fun getMetaMember(method: PsiElement): IMetaMember? {
        val languageSupport = LanguageSupport.forLanguage(method.language) ?: return null
        val metaClass = getMetaClass(languageSupport.getContainingClass(method)) ?: return null
        return metaClass.metaMembers.find { languageSupport.matchesSignature(it, method) }
    }

    fun loadBytecodeAsync(file: PsiFile, callback: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            loadBytecode(file)

            SwingUtilities.invokeLater {
                callback()
            }
        }
    }

    fun loadBytecode(file: PsiFile) {
        val module = ModuleUtil.findModuleForPsiElement(file) ?: return
        val outputRoots = CompilerModuleExtension.getInstance(module)!!.getOutputRoots(true)
                .map { it.canonicalPath }

        val annotationsMap = hashMapOf<IMetaMember, BytecodeAnnotations>()
        val allClasses = LanguageSupport.forElement(file).getAllClasses(file)
        for (cls in allClasses) {
            val metaClass = ApplicationManager.getApplication().runReadAction(Computable { getMetaClass(cls) }) ?: continue
            metaClass.getClassBytecode(JitWatchModelService.getInstance(project).model, outputRoots)
            buildAllBytecodeAnnotations(metaClass, annotationsMap)
        }
        bytecodeAnnotations[file.virtualFile] = annotationsMap
    }

    private fun buildAllBytecodeAnnotations(metaClass: MetaClass, target: MutableMap<IMetaMember, BytecodeAnnotations>) {
        for (metaMember in metaClass.metaMembers) {
            val annotations = try {
                BytecodeAnnotationBuilder().buildBytecodeAnnotations(metaMember, model)
            } catch (e: Exception) {
                LOG.error("Failed to build annotations", e)
                continue
            }
            target[metaMember] = annotations
        }
    }

    fun processBytecodeAnnotations(psiFile: PsiFile, callback: (method: PsiElement,
                                                                member: IMetaMember,
                                                                memberBytecode: MemberBytecode,
                                                                instruction: BytecodeInstruction,
                                                                annotations: List<LineAnnotation>) -> Unit) {
        val languageSupport = LanguageSupport.forLanguage(psiFile.language)
        val annotationsForFile = bytecodeAnnotations[psiFile.virtualFile] ?: return
        for (cls in languageSupport.getAllClasses(psiFile)) {
            val classBC = getMetaClass(cls)?.classBytecode ?: continue
            for (method in languageSupport.getAllMethods(cls)) {
                val member = annotationsForFile.keys.find { languageSupport.matchesSignature(it, method) } ?: continue
                val annotations = annotationsForFile[member] ?: continue
                val memberBytecode = classBC.getMemberBytecode(member) ?: continue
                for (instruction in memberBytecode.instructions) {
                    val annotationsForBCI = annotations.getAnnotationsForBCI(instruction.offset)
                    if (annotationsForBCI.isNullOrEmpty()) continue

                    callback(method, member, memberBytecode, instruction, annotationsForBCI)
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project) = ServiceManager.getService(project, JitWatchModelService::class.java)

        val LOG = Logger.getInstance(JitWatchModelService::class.java)
    }
}
