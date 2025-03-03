package com.example.cameratesting.ui.theme

class Alias(var word: String, var aliases : ArrayList<String>,
            var ignoreCase: Boolean =true, var ignorePunctuation : Boolean = true) {
    init {
        if (ignoreCase) {
            word = word.lowercase()
            aliases.replaceAll { it.lowercase() }
        }
        if (ignorePunctuation) {
            word = removePunctuation(word)
            aliases.replaceAll { removePunctuation(it) }
        }
    }
    companion object {
        fun removePunctuation(str:String):String {
            return str.replace(Regex("[^\\w\\s]"), "")
        }
    }
    fun matches(strParam : String) : Boolean {
        var str = if(ignoreCase) strParam.lowercase() else strParam
        if (ignorePunctuation)
            str = removePunctuation(str)
        return aliases.contains(str)
    }
    fun isIn(strParam : String) : Boolean {
        var str = if(ignoreCase) strParam.lowercase() else strParam
        if (ignorePunctuation)
            str = removePunctuation(str)
        for (s in aliases) {
            if (str.contains(s)) {
                return true
            }
        }
        return false
    }
    fun removeSelf(strParam : String) : String {
        var str = if(ignoreCase) strParam.lowercase() else strParam
        if (ignorePunctuation)
            str = removePunctuation(str)
        for (s in aliases) {
            if (str.contains(s))
                return str.replace(s, "").trim()
        }
        return str
    }
}