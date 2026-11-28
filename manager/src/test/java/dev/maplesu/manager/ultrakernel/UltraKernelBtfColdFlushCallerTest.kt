package dev.maplesu.manager.ultrakernel

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class UltraKernelBtfColdFlushCallerTest {
    private val prefix=0x5435fce0
    private data class Fixture(val raw:ByteArray,val symbols:UltraKernelKallsymsTable)
    private fun fixture(cold:Boolean=true, memberOffset:Int=120, parentOffset:Int=40,
        typedFile:Boolean=true, sourceArgument:Int=0, callbackArgument:Int=19,
        validGuard:Boolean=true, wrongGuardPointer:Boolean=false, ambiguousLoads:Boolean=false,
        bypassLoad:Boolean=false, operation:Int=0xaa0103f4.toInt(), conflictingPrototype:Boolean=false):Fixture {
        val strings=ByteArrayOutputStream().apply {write(0)}
        fun name(value:String)=strings.size().also {strings.write(value.toByteArray());strings.write(0)}
        val types=ByteArrayOutputStream()
        fun word(value:Int) {repeat(4) {types.write(value ushr (it*8) and 255)}}
        fun record(label:String,kind:Int,value:Int,count:Int=0,vararg extra:Int) {
            word(if(label.isEmpty())0 else name(label));word(kind shl 24 or count);word(value);extra.forEach(::word)
        }
        record("int",1,4,0,0x01000020) //1
        record("file",4,264,1,name("f_op"),3,40*8) //2
        record("",2,4) //3 fops pointer
        record("file_operations",4,272,2,name("flush"),5,120*8,name("release"),5,128*8) //4
        record("",2,6) //5 callback pointer
        record("",13,1,2,0,7,0,8) //6 int(file*,void*)
        record("",2,2) //7 file*
        record("",2,0) //8 void*
        record("",13,1,2,0,if(typedFile)7 else 8,0,8) //9 caller proto
        record("filp_close",12,9) //10
        if(conflictingPrototype) {
            record("",13,1,2,0,8,0,8) //11
            record("filp_close",12,11)
        }
        val body=types.toByteArray();val text=strings.toByteArray();val btf=1024
        val raw=ByteArray(btf+24+body.size+text.size)
        fun code(at:Int,value:Int)=UltraKernelA64.writeLe32(raw,at,value.toLong())
        for(at in 64 until 256 step 4)code(at,0xd503201f.toInt())
        fun branch(at:Int,target:Int)=code(at,0x14000000 or ((target-at)/4 and 0x03ffffff))
        fun conditional(at:Int,target:Int,register:Int,nonzero:Boolean)=
            code(at,(if(nonzero)0xb5000000.toInt() else 0xb4000000.toInt()) or (((target-at)/4 and 0x7ffff) shl 5) or register)
        code(64,0xaa0003f3.toInt() or (sourceArgument shl 16)) //mov x19, source
        code(68,operation) //mov x20,x1, or an explicitly tested operation
        code(72,0xf9400261.toInt() or ((parentOffset/8) shl 10)) //ldr x1,[x19,#f_op]
        val block=if(cold)128 else 84
        if(ambiguousLoads) {
            conditional(76,112,20,false)
            code(80,0xf9400028.toInt() or ((memberOffset/8) shl 10));branch(84,block)
            code(112,0xf9400028.toInt() or ((memberOffset/8) shl 10));branch(116,block)
        } else {
            code(76,0xf9400028.toInt() or ((memberOffset/8) shl 10)) //ldr x8,[x1,#flush]
            if(cold) {conditional(80,block,8,true);code(84,0xd65f03c0.toInt())}
        }
        if(bypassLoad)conditional(68,block,20,false)
        code(block,0xaa0003e0.toInt() or (callbackArgument shl 16)) //mov x0, saved file
        code(block+4,0xaa1403e1.toInt())
        code(block+8,if(!validGuard)0xd503201f.toInt() else if(wrongGuardPointer)0xb85fc130.toInt() else 0xb85fc110.toInt())
        code(block+12,0x52800011 or ((prefix and 65535) shl 5))
        code(block+16,0x72a00011 or ((prefix ushr 16) shl 5))
        code(block+20,0x6b11021f)
        code(block+24,0x54000040)
        code(block+28,0xd4200000.toInt() or ((0x8000 or (17 shl 5) or 8) shl 5))
        code(block+32,0xd63f0100.toInt());code(block+36,0xd65f03c0.toInt())
        listOf(0x0001EB9F,24,0,body.size,body.size,text.size).forEachIndexed {i,value->code(btf+i*4,value)}
        body.copyInto(raw,btf+24);text.copyInto(raw,btf+24+body.size)
        val names=hashMapOf("__start_BTF" to btf,"_stext" to 4,"_etext" to 512,"filp_close" to 64,"caller_end" to 256)
        val constructor=UltraKernelKallsymsTable::class.java.declaredConstructors.single {it.parameterCount==15}.apply {isAccessible=true}
        val symbols=constructor.newInstance(raw.size,0L,0L,names.size,names,emptyMap<String,List<Int>>(),
            HashSet(names.values),names.values.distinct().sorted().toIntArray(),emptyMap<String,List<Int>>(),
            intArrayOf(),0,0,0,0,0) as UltraKernelKallsymsTable
        return Fixture(raw,symbols)
    }
    private fun ids(value:Fixture, signature:String?=null):Set<Int> {
        val expected=signature ?: UltraKernelBtf.memberSignature(value.raw,value.symbols,"file_operations","flush")!!.removePrefix("*")
        return UltraKernelBtf.sessionCallbackTypeids(value.raw,value.symbols,mapOf("file_operations.flush" to expected)).getValue("file_operations.flush")
    }
    private fun rejected(value:Fixture)=assertThrows(UltraKernelGenerateException::class.java) {ids(value)}
    @Test fun `cold flush block after return retains typed member and argument`()=assertEquals(setOf(prefix),ids(fixture()))
    @Test fun `existing linear flush form stays supported`()=assertEquals(setOf(prefix),ids(fixture(cold=false)))
    @Test fun `same signature at release field is rejected`() {rejected(fixture(memberOffset=128))}
    @Test fun `adjacent file field is not f_op`() {rejected(fixture(parentOffset=48))}
    @Test fun `untyped filp_close source is rejected`() {rejected(fixture(typedFile=false))}
    @Test fun `saved opaque argument is not a file pointer`() {rejected(fixture(sourceArgument=1))}
    @Test fun `callback argument must retain the same typed source`() {rejected(fixture(callbackArgument=20))}
    @Test fun `guard is still required for a proven cold load`() {assertTrue(rejected(fixture(validGuard=false)).detail.contains("SESSION_CALLER_GUARD:"))}
    @Test fun `guard load must use the called pointer`() {assertTrue(rejected(fixture(wrongGuardPointer=true)).detail.contains("SESSION_CALLER_GUARD:"))}
    @Test fun `multiple reaching member loads remain ambiguous`() {rejected(fixture(ambiguousLoads=true))}
    @Test fun `entry path bypassing the member load is rejected`() {rejected(fixture(bypassLoad=true))}
    @Test fun `unknown instruction on the typed source path is rejected`() {rejected(fixture(operation=0xffffffff.toInt()))}
    @Test fun `conflicting caller prototype is rejected`() {rejected(fixture(conflictingPrototype=true))}
    @Test fun `requested callback type must equal the actual BTF member`() {
        assertThrows(UltraKernelGenerateException::class.java) {ids(fixture(),"fv(*sfile;*v;)")}
    }
}
