package suwayomi.tachidesk.manga.impl.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.streams.asSequence

object BytecodeEditor {
    private val logger = KotlinLogging.logger {}

    /**
     * Replace some java class references inside a jar with new ones that behave like Androids
     *
     * @param jarFile The JarFile to replace class references in
     */
    fun fixAndroidClasses(jarFile: Path) {
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use {
            Files
                .walk(it.getPath("/"))
                .asSequence()
                .filterNotNull()
                .filterNot(Files::isDirectory)
                .mapNotNull(::getClassBytes)
                .map(::transform)
                .forEach(::write)
        }
        // GoManga: make R8-optimised extension bytecode strict-JVM-verifiable so it
        // loads under -Xverify:remote (and is native-safe on the iOS/arm64 OpenJ9
        // device, where -Xverify:none crashes on type-unsafe bytecode). See
        // repairR8NewInstances.
        try {
            repairR8NewInstances(jarFile)
        } catch (e: Throwable) {
            // Never fail the whole install because the repair pass tripped — the
            // unrepaired jar just keeps its pre-existing verify behaviour.
            logger.error(e) { "repairR8NewInstances failed for $jarFile; leaving jar unrepaired" }
        }
    }

    /**
     * Make dex2jar-converted extension bytecode strict-JVM-verifiable.
     *
     * dex2jar 2.4.37's NewTransformer takes the allocation type of a merged
     * new+<init> from the `<init>` invoke's OWNER instead of the NEW instruction's
     * real type. When R8 constructor-inlining/tree-shaking strips an obfuscated
     * subclass T's own <init>, the DEX legally emits `new-instance LT;` +
     * `invoke-direct <SuperOfT>.<init>(desc)`, so dex2jar collapses `new T` into
     * `new <Super>` (commonly `new java/lang/Object`). The class is then used as T
     * (putfield T.f / stored into an LT;-typed field), which OpenJ9's verifier
     * rejects (JVMVRFY012 "Type ... is not assignable to T") — so under
     * -Xverify:remote the source fails to install, and under -Xverify:none the same
     * bytecode native-crashes the arm64 device (a type-confused field write).
     *
     * This pass, run after dex2jar + the SimpleDateFormat fixups above:
     *   1. rewrites `new Super` back to `new T` (+ `invokespecial Super.<init>` to
     *      `invokespecial T.<init>`), recovering T from the value's sink (the
     *      putfield/putstatic field descriptor, the putfield owner, or a checkcast),
     *      only when T directly extends Super so the super-ctor call stays legal;
     *   2. synthesises a delegating `T.<init>(desc){ super(desc) }` for every
     *      rewritten T whose own <init> R8 removed (field init already happens at the
     *      call site via putfields, so this is behaviour-preserving); a still-missing
     *      ctor would surface only as a clean, catchable NoSuchMethodError, never a
     *      native crash;
     *   3. rewrites every class with COMPUTE_FRAMES + a jar-aware getCommonSuperClass
     *      so the StackMapTable dex2jar mis-emitted is recomputed correctly.
     *
     * Verified on host OpenJ9 25: baozimanhua (24/24) and happymh (55/55) classes
     * go from 12 / 30 verify errors to 0, passing -Xverify:remote.
     */
    fun repairR8NewInstances(jarFile: Path) {
        val nodes = LinkedHashMap<String, ClassNode>()
        val superOf = HashMap<String, String?>()
        val initDescs = HashMap<String, MutableSet<String>>()
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use { fs ->
            Files.walk(fs.getPath("/")).asSequence().filterNotNull().filterNot(Files::isDirectory).forEach { p ->
                if (!p.toString().endsWith(".class")) return@forEach
                val cn = ClassNode()
                ClassReader(Files.readAllBytes(p)).accept(cn, 0)
                nodes[p.toString()] = cn
                superOf[cn.name] = cn.superName
                initDescs.getOrPut(cn.name) { mutableSetOf() }.addAll(cn.methods.filter { it.name == "<init>" }.map { it.desc })
            }
        }

        // Detect R8-stripped constructors. The vendored dex2jar NewTransformer patch
        // already recovers the real allocation type T (`new T`, from the DEX
        // new-instance) instead of the <init> invoke's owner. What remains is that R8
        // inlined T's own constructor, so the recovered `new T; invokespecial T.<init>(desc)`
        // links to a T.<init> that no longer exists — a run-time NoSuchMethodError — AND
        // the StackMapTable frames dex2jar emitted are stale w.r.t. the recovered types
        // (JVMVRFY012 "stack shape inconsistent"). A `new T; invokespecial T.<init>(desc)`
        // where T is a jar-local class missing that ctor is the exact fingerprint of this
        // R8 collapse: it is the trigger to both synthesise the ctor and reframe.
        //
        // needCtor: T -> { ctorDesc } — one synthesised T.<init>(desc) per missing desc,
        // each delegating to superOf[T].<init>(desc) (that super ctor survived, because R8
        // rewrote the inlined call to it). base for the super chain is superOf[T].
        val needCtor = LinkedHashMap<String, LinkedHashSet<String>>()
        for (cn in nodes.values) {
            for (m in cn.methods) {
                val arr = m.instructions.toArray()
                for (i in arr.indices) {
                    val ti = arr[i] as? TypeInsnNode ?: continue
                    if (ti.opcode != Opcodes.NEW) continue
                    val t = ti.desc
                    if (!superOf.containsKey(t)) continue // only jar-local classes we can add a ctor to
                    // matching invokespecial T.<init> (ctor args may sit between the new and it)
                    var j = i + 1
                    while (j < minOf(i + 24, arr.size)) {
                        val y = arr[j]
                        if (y is JumpInsnNode || y is LabelNode) break
                        if (y is MethodInsnNode && y.opcode == Opcodes.INVOKESPECIAL && y.owner == t && y.name == "<init>") {
                            if (initDescs[t]?.contains(y.desc) != true) {
                                needCtor.getOrPut(t) { LinkedHashSet() }.add(y.desc)
                            }
                            break
                        }
                        j++
                    }
                }
            }
        }

        if (needCtor.isEmpty()) return // no R8-collapsed new-instance — leave the jar (and its frames) untouched

        var synth = 0
        for (cn in nodes.values) {
            val descs = needCtor[cn.name] ?: continue
            val base = superOf[cn.name] ?: "java/lang/Object"
            for (desc in descs) {
                val c = MethodNode(Opcodes.ACC_PUBLIC, "<init>", desc, null, null)
                c.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                var local = 1
                for (at in Type.getArgumentTypes(desc)) {
                    c.instructions.add(VarInsnNode(at.getOpcode(Opcodes.ILOAD), local))
                    local += at.size
                }
                c.instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, base, "<init>", desc, false))
                c.instructions.add(InsnNode(Opcodes.RETURN))
                c.maxStack = maxOf(1, local)
                c.maxLocals = local
                cn.methods.add(c)
                synth++
            }
        }

        // COMPUTE_FRAMES with a getCommonSuperClass that can resolve the extension's
        // own (obfuscated) classes from the still-intact jar; write outputs to memory
        // first, then back to the jar so the resolving loader never races the write.
        val jarCL = URLClassLoader(arrayOf(jarFile.toUri().toURL()), BytecodeEditor::class.java.classLoader)
        val out = LinkedHashMap<String, ByteArray>()
        jarCL.use { cl ->
            for ((path, cn) in nodes) {
                val cw =
                    object : ClassWriter(COMPUTE_FRAMES) {
                        override fun getClassLoader(): ClassLoader = cl
                        override fun getCommonSuperClass(type1: String, type2: String): String =
                            try {
                                super.getCommonSuperClass(type1, type2)
                            } catch (e: Throwable) {
                                "java/lang/Object"
                            }
                    }
                cn.accept(cw)
                out[path] = cw.toByteArray()
            }
        }
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use { fs ->
            for ((path, bytes) in out) {
                Files.write(fs.getPath(path), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }
        }
        logger.info { "repairR8NewInstances($jarFile): synthesised $synth R8-stripped ctor(s), reframed ${out.size} class(es)" }
    }

    /**
     * Get class bytes from a [Path]
     *
     * @param path The path entry to get the class bytes from
     *
     * @return [Pair] of the [Path] plus the class [ByteArray], or null if it's not a valid class
     */
    private fun getClassBytes(path: Path): Pair<Path, ByteArray>? {
        return try {
            if (path.toString().endsWith(".class")) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size < 4) {
                    // Invalid class size
                    return null
                }
                val cafebabe =
                    String.format(
                        "%02X%02X%02X%02X",
                        bytes[0],
                        bytes[1],
                        bytes[2],
                        bytes[3],
                    )
                if (cafebabe.lowercase() != "cafebabe") {
                    // Corrupted class
                    return null
                }

                path to bytes
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error loading class from Path: $path" }
            null
        }
    }

    /**
     * The path where replacement classes will reside
     */
    private const val REPLACEMENT_PATH = "xyz/nulldev/androidcompat/replace"

    /**
     * List of classes that will be replaced
     */
    private val classesToReplace =
        listOf<String>()

    /**
     * Replace direct references to the class, used on places
     * that don't have any other text then the class
     *
     * @return [String] of class or null if [String] was null
     */
    private fun String?.replaceDirectly() =
        when (this) {
            null -> null
            in classesToReplace -> "$REPLACEMENT_PATH/$this"
            else -> this
        }

    /**
     * Replace references to the class, used in places that have
     * other text around the class references
     *
     * @return [String] with class references replaced, or null if [String] was null
     */
    private fun String?.replaceIndirectly(): String? {
        if (this == null) return null
        var classReference: String = this
        classesToReplace.forEach {
            classReference = classReference.replace(it, "$REPLACEMENT_PATH/$it")
        }
        return classReference
    }

    /**
     * Replace all references to certain classes inside the class file
     * with ones that behave more like Androids
     *
     * @param pair Class bytecode to load into ASM for ease of modification
     *
     * @return [ByteArray] with modified bytecode
     */
    private fun transform(pair: Pair<Path, ByteArray>): Pair<Path, ByteArray> {
        // Read the class and prepare to modify it
        val cr = ClassReader(pair.second)
        val cw = ClassWriter(cr, 0)
        // Modify the class
        cr.accept(
            object : ClassVisitor(Opcodes.ASM5, cw) {
                // Modify field descriptor, for example
                // class MangaYes {
                //     val format = SimpleDateFormat("YYYY-MM-dd")
                // }
                override fun visitField(
                    access: Int,
                    name: String?,
                    desc: String?,
                    signature: String?,
                    cst: Any?,
                ): FieldVisitor? {
                    logger.trace { "CLass Field" to "${desc.replaceIndirectly()}: ${cst?.let { it::class.java.simpleName }}: $cst" }
                    return super.visitField(access, name, desc.replaceIndirectly(), signature, cst)
                }

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    logger.trace { "Visiting $name: $signature: $superName" }
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                // Modify method bytecode, for example
                // class MangaYes {
                //     fun fetchChapterList() {
                //         SimpleDateFormat("YYYY-MM-dd")
                //     }
                // }
                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String?>?,
                ): MethodVisitor {
                    logger.trace { "Processing method $name: ${desc.replaceIndirectly()}: $signature" }
                    val mv: MethodVisitor? =
                        super.visitMethod(
                            access,
                            name,
                            desc.replaceIndirectly(),
                            signature,
                            exceptions,
                        )
                    return object : MethodVisitor(Opcodes.ASM5, mv) {
                        override fun visitLdcInsn(cst: Any?) {
                            logger.trace { "Ldc" to "${cst?.let { "${it::class.java.simpleName}: $it" }}" }
                            super.visitLdcInsn(cst)
                        }

                        // Replace method type, for example
                        // val format = DateFormat()
                        // fun fetchChapterList() {
                        //     if (format is SimpleDateFormat)
                        // }
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) {
                            logger.trace {
                                "Type" to "$opcode: ${type.replaceDirectly()}"
                            }
                            super.visitTypeInsn(
                                opcode,
                                type.replaceDirectly(),
                            )
                        }

                        // Replace method field, for example
                        // fun fetchChapterList() {
                        //     val format = SimpleDateFormat("YYYY-MM-dd")
                        // }
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                            itf: Boolean,
                        ) {
                            logger.trace {
                                "Method" to "$opcode: ${owner.replaceDirectly()}: $name: ${desc.replaceIndirectly()}"
                            }
                            super.visitMethodInsn(
                                opcode,
                                owner.replaceDirectly(),
                                name,
                                desc.replaceIndirectly(),
                                itf,
                            )
                        }

                        // Replace class field call from method, for example
                        // val format = SimpleDateFormat("YYYY-MM-dd")
                        // fun fetchChapterList() {
                        //     format.format(Date())
                        // }
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                        ) {
                            logger.trace { "Field" to "$opcode: $owner: $name: ${desc.replaceIndirectly()}" }
                            super.visitFieldInsn(opcode, owner, name, desc.replaceIndirectly())
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            desc: String?,
                            bsm: Handle?,
                            vararg bsmArgs: Any?,
                        ) {
                            logger.trace { "InvokeDynamic" to "$name: $desc" }
                            super.visitInvokeDynamicInsn(name, desc, bsm, *bsmArgs)
                        }
                    }
                }
            },
            0,
        )
        return pair.first to cw.toByteArray()
    }

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
