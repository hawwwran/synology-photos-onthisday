package com.hawwwran.photosonthisday.api

/**
 * DSM's error codes in plain Czech, for the screen. The raw code is appended so what the user
 * sees is still what DSM said (plan.md "Errors" convention). The app's UI is Czech, so these
 * are too. The Auth and common codes are from Synology's published Web API guide; see
 * `documents/research/photos-web-api.md` under "Signing in".
 *
 * The same number means different things per api: 407 is "address blocked by auto-block" on a
 * login and "operation not permitted" in File Station, so the text is chosen by the failing
 * call's api, not by the code alone.
 */
object DsmErrorText {

    fun forLogin(code: Int): String = "${loginReason(code)} (chyba DSM $code)"

    fun forCall(code: Int): String = "${commonReason(code)} (chyba DSM $code)"

    /** File Station's own codes, for the likes file (decision 008). */
    fun forFileStation(code: Int): String = "${fileStationReason(code)} (chyba DSM $code)"

    fun forFailure(failure: ApiFailure): String = when (failure) {
        is ApiFailure.DsmError -> when {
            failure.call.api.startsWith(FILE_STATION_PREFIX) -> forFileStation(failure.code)
            failure.call == Allowlist.LOGIN -> forLogin(failure.code)
            else -> forCall(failure.code)
        }
        is ApiFailure.SessionExpired -> "NAS ukončil relaci. Přihlaste se znovu. (chyba DSM ${failure.code})"
        is ApiFailure.Transport -> "NAS není dostupný. Zkontrolujte připojení k internetu."
        is ApiFailure.Malformed -> malformedReason(failure)
    }

    /**
     * An answer that was not a Synology envelope. On the sign-in path that means the address is
     * not a NAS, which is the text the screen has always shown. Elsewhere it is a status or a shape
     * the user can act on, so it is named: a File Station refusal of the likes file usually means
     * the folder or the permission, and the app must not blame the address for it.
     */
    private fun malformedReason(failure: ApiFailure.Malformed): String {
        val status = MalformedDetail.httpStatus(failure.detail)
        val fileStation = failure.call.api.startsWith(FILE_STATION_PREFIX)
        return when {
            failure.detail == MalformedDetail.UNREADABLE_LIKES_FILE ->
                "Soubor likes.json ve složce pro lajky se nepodařilo přečíst. Nebyl přepsán, aby se lajky neztratily; opravte ho, nebo smažte a lajky se uloží znovu."
            fileStation && status != null ->
                "NAS odmítl soubor s lajky (HTTP $status). Zkontrolujte, že složka pro lajky existuje a že do ní účet smí zapisovat."
            fileStation ->
                "NAS odpověděl na soubor s lajky neočekávaně (${failure.detail})."
            status != null -> "Adresa odpověděla chybou HTTP $status, ne jako Synology NAS."
            else -> "Adresa odpověděla, ale ne jako Synology NAS."
        }
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

    /**
     * Why the likes file could not be read or written. The three the household is likely to meet
     * name the DSM setting to change: File Station not permitted for the account, no write
     * permission on the folder, and a missing folder (usually the user home service being off).
     */
    private fun fileStationReason(code: Int): String = when (code) {
        105 -> "Účet nemá na NAS povolenou aplikaci File Station. Povolte ji v Ovládacím panelu, Uživatel a skupina, u účtu v Aplikace."
        400 -> "NAS odmítl parametr operace se souborem."
        401 -> "NAS nahlásil neznámou chybu operace se souborem."
        402 -> "NAS je právě příliš zatížený. Zkuste to znovu."
        403 -> "Tento účet nesmí se soubory na NAS pracovat."
        406 -> "NAS nezískal informace o účtu."
        407 -> "Ke složce pro lajky nemá účet právo zápisu. Změňte oprávnění složky, nebo v Nastavení zvolte jinou."
        408 -> "Složka pro lajky na NAS neexistuje a nepodařilo se ji vytvořit. Zapněte domovské složky uživatelů (Ovládací panel, Uživatel a skupina, Rozšířené), nebo v Nastavení zvolte složku, kam účet smí zapisovat."
        409 -> "Tento souborový systém to nepodporuje."
        411 -> "Přístup ke složce pro lajky byl odepřen."
        1805 -> "Soubor s lajky na NAS nelze přepsat."
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

    private const val FILE_STATION_PREFIX = "SYNO.FileStation."
}
