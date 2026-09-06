package app.shackcq.mobile

import java.io.InputStream
import java.io.InputStreamReader

data class AdifImportProgress(val parsed:Int,val inserted:Int,val duplicates:Int,val invalid:Int)

internal fun streamAdifRecords(input:InputStream,cancelled:()->Boolean={false},record:(String)->Unit):Pair<Int,Int>{
    val reader=InputStreamReader(input,Charsets.UTF_8);val buffer=CharArray(16_384);val pending=StringBuilder();var parsed=0;var invalid=0
    while(true){
        if(cancelled())break
        val read=reader.read(buffer);if(read<0)break
        pending.append(buffer,0,read)
        while(true){
            val end=pending.indexOf("<EOR>",ignoreCase=true);if(end<0)break
            val value=pending.substring(0,end+5);pending.delete(0,end+5);record(value);parsed++
        }
        if(pending.length>2_000_000){pending.clear();invalid++}
    }
    if(pending.isNotBlank())invalid++
    return parsed to invalid
}

private fun StringBuilder.indexOf(value:String,ignoreCase:Boolean):Int {
    if(length<value.length)return -1
    for(index in 0..length-value.length)if(regionMatches(index,value,ignoreCase))return index
    return -1
}
private fun StringBuilder.regionMatches(offset:Int,value:String,ignoreCase:Boolean):Boolean {
    for(index in value.indices)if(!this[offset+index].equals(value[index],ignoreCase))return false
    return true
}
