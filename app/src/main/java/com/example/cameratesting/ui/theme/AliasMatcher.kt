package com.example.cameratesting.ui.theme

import com.example.cameratesting.ui.theme.Alias.Companion.removePunctuation

class AliasMatcher(var ignoreCase : Boolean = true, var ignorePunctuation : Boolean = true) {
    val aliases = mutableMapOf<String, Alias>()
    fun addAlias(alias : Alias) {
        aliases[alias.word] = alias
    }
    fun matches(string1 : String, string2 : String) : Boolean {
        var s1 = if(ignoreCase) string1.lowercase() else string1
        if (ignorePunctuation)
            s1 = removePunctuation(s1)
        var s2 = if(ignoreCase) string2.lowercase() else string2
        if (ignorePunctuation)
            s2 = removePunctuation(s2)
        if (s1 == s2)
            return true
        if (aliases.containsKey(s2)) {
            return aliases[s2]?.matches(s1) ?: false}
        else
            return false
    }
    fun contains(string1 : String, string2 : String) : Boolean {
        var s1 = if(ignoreCase) string1.lowercase() else string1
        if (ignorePunctuation)
            s1 = removePunctuation(s1)
        var s2 = if(ignoreCase) string2.lowercase() else string2
        if (ignorePunctuation)
            s2 = removePunctuation(s2)
        if (s1 == s2)
            return true
        if (aliases.containsKey(s2)) {
            return aliases[s2]?.isIn(s1) ?: false}
        else
            return false
    }
    fun remove(string1 : String, string2 : String) : String? {
        var s1 = if(ignoreCase) string1.lowercase() else string1
        if (ignorePunctuation)
            s1 = removePunctuation(s1)
        var s2 = if(ignoreCase) string2.lowercase() else string2
        if (ignorePunctuation)
            s2 = removePunctuation(s2)
        if (s1 == s2)
            return ""
        if (aliases.containsKey(s2))
            return aliases[s2]?.removeSelf(s1) ?: null
        else
            return null
    }
}