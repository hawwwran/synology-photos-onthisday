package com.hawwwran.photosonthisday.api

/**
 * DSM's error codes in plain Czech, for the screen. The raw code is appended so what the user
 * sees is still what DSM said (plan.md "Errors" convention). The app's UI is Czech, so these
 * are too. The Auth and common codes are from Synology's published Web API guide; see
 * `documents/research/photos-web-api.md` under "Signing in".
 */
object DsmErrorText {

    fun forLogin(code: Int): String = "${loginReason(code)} (chyba DSM $code)"

    fun forCall(code: Int): String = "${commonReason(code)} (chyba DSM $code)"

    fun forFailure(failure: ApiFailure): String = when (failure) {
        is ApiFailure.DsmError -> forCall(failure.code)
        is ApiFailure.SessionExpired -> "NAS ukončil relaci. Přihlaste se znovu. (chyba DSM ${failure.code})"
        is ApiFailure.Transport -> "NAS není dostupný. Zkontrolujte připojení k internetu."
        is ApiFailure.Malformed -> "Adresa odpověděla, ale ne jako Synology NAS."
    }

    private fun loginReason(code: Int): String = when (code) {
        400 -> "Nesprávné jméno účtu nebo heslo."
        401 -> "Tento účet je zakázán."
        402 -> "Tento účet se sem nesmí přihlásit."
        403 -> "Je vyžadován dvoufaktorový kód."
        404 -> "Dvoufaktorový kód byl nesprávný."
        406 -> "Pro tento účet je vyžadováno dvoufaktorové ověření."
        407 -> "Tato adresa je blokována NAS. Automatické blokování DSM ji po čase zruší, nebo správce."
        409 -> "Platnost hesla vypršela."
        410 -> "Před přihlášením je nutné změnit heslo."
        else -> commonReason(code)
    }

    private fun commonReason(code: Int): String = when (code) {
        100 -> "NAS nahlásil neznámou chybu."
        101 -> "NAS hlásí chybějící parametr."
        102 -> "NAS toto API nemá."
        103 -> "NAS tuto metodu nemá."
        104 -> "NAS nepodporuje tuto verzi API."
        105 -> "Tento účet k tomu nemá oprávnění."
        106 -> "Relace vypršela."
        107 -> "Tuto relaci ukončilo jiné přihlášení."
        119 -> "Relace už není platná."
        120 -> "NAS odmítl parametr."
        else -> "NAS odmítl požadavek."
    }
}
