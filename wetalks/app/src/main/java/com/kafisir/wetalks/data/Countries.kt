package com.kafisir.wetalks.data

/** Dial code plus the allowed length of the national number (without trunk "0"). */
class Country(val iso: String, val name: String, val dial: String, val min: Int, val max: Int) {
    val flag: String get() = iso.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

object Countries {
    val all = listOf(
        Country("IN", "India", "91", 10, 10), Country("US", "United States", "1", 10, 10), Country("CA", "Canada", "1", 10, 10), Country("GB", "United Kingdom", "44", 10, 10),
        Country("PK", "Pakistan", "92", 10, 10), Country("BD", "Bangladesh", "880", 10, 10), Country("NP", "Nepal", "977", 10, 10), Country("LK", "Sri Lanka", "94", 9, 9),
        Country("BT", "Bhutan", "975", 8, 8), Country("MV", "Maldives", "960", 7, 7), Country("AF", "Afghanistan", "93", 9, 9),
        Country("AE", "United Arab Emirates", "971", 9, 9), Country("SA", "Saudi Arabia", "966", 9, 9), Country("QA", "Qatar", "974", 8, 8), Country("KW", "Kuwait", "965", 8, 8),
        Country("OM", "Oman", "968", 8, 8), Country("BH", "Bahrain", "973", 8, 8), Country("IR", "Iran", "98", 10, 10), Country("IQ", "Iraq", "964", 10, 10),
        Country("IL", "Israel", "972", 9, 9), Country("JO", "Jordan", "962", 9, 9), Country("LB", "Lebanon", "961", 7, 8), Country("TR", "Turkey", "90", 10, 10),
        Country("ID", "Indonesia", "62", 9, 12), Country("MY", "Malaysia", "60", 9, 10), Country("SG", "Singapore", "65", 8, 8), Country("TH", "Thailand", "66", 9, 9),
        Country("VN", "Vietnam", "84", 9, 10), Country("PH", "Philippines", "63", 10, 10), Country("MM", "Myanmar", "95", 8, 10), Country("KH", "Cambodia", "855", 8, 9),
        Country("CN", "China", "86", 11, 11), Country("JP", "Japan", "81", 10, 10), Country("KR", "South Korea", "82", 9, 10), Country("AU", "Australia", "61", 9, 9), Country("NZ", "New Zealand", "64", 8, 10),
        Country("DE", "Germany", "49", 10, 11), Country("FR", "France", "33", 9, 9), Country("IT", "Italy", "39", 9, 10), Country("ES", "Spain", "34", 9, 9), Country("PT", "Portugal", "351", 9, 9),
        Country("NL", "Netherlands", "31", 9, 9), Country("BE", "Belgium", "32", 8, 9), Country("CH", "Switzerland", "41", 9, 9), Country("AT", "Austria", "43", 10, 11),
        Country("SE", "Sweden", "46", 9, 9), Country("NO", "Norway", "47", 8, 8), Country("DK", "Denmark", "45", 8, 8), Country("FI", "Finland", "358", 9, 10), Country("IE", "Ireland", "353", 9, 9),
        Country("PL", "Poland", "48", 9, 9), Country("RU", "Russia", "7", 10, 10), Country("UA", "Ukraine", "380", 9, 9),
        Country("EG", "Egypt", "20", 10, 10), Country("ZA", "South Africa", "27", 9, 9), Country("NG", "Nigeria", "234", 10, 10), Country("KE", "Kenya", "254", 9, 9), Country("GH", "Ghana", "233", 9, 9),
        Country("ET", "Ethiopia", "251", 9, 9), Country("MA", "Morocco", "212", 9, 9), Country("DZ", "Algeria", "213", 9, 9),
        Country("BR", "Brazil", "55", 10, 11), Country("MX", "Mexico", "52", 10, 10), Country("AR", "Argentina", "54", 10, 10), Country("CO", "Colombia", "57", 10, 10),
        Country("CL", "Chile", "56", 9, 9), Country("PE", "Peru", "51", 9, 9),
    )

    fun byIso(iso: String) = all.firstOrNull { it.iso.equals(iso, true) } ?: all[0]

    /** Splits "+919876543210" into (India, "9876543210"). */
    fun parse(full: String): Pair<Country, String>? {
        val d = full.trim().filter { it.isDigit() || it == '+' }
        if (!d.startsWith("+")) return null
        val digits = d.drop(1)
        val c = all.filter { digits.startsWith(it.dial) }.maxByOrNull { it.dial.length } ?: return null
        return c to digits.removePrefix(c.dial)
    }

    /** Cleans what the user typed: digits only, no trunk "0", capped to the country's maximum. */
    fun clean(c: Country, typed: String): String {
        var d = typed.filter { it.isDigit() }
        if (d.startsWith("0") && c.iso != "IT") d = d.trimStart('0')
        return d.take(c.max)
    }

    /** Returns an error message, or null when the number is fine. */
    fun validate(c: Country, national: String): String? {
        val d = national.filter { it.isDigit() }
        if (d.isEmpty()) return "Enter your phone number"
        if (d.length < c.min || d.length > c.max) return if (c.min == c.max) "${c.name} numbers have ${c.min} digits" else "${c.name} numbers have ${c.min}-${c.max} digits"
        if (c.iso == "IN" && d[0] !in '6'..'9') return "Indian mobile numbers start with 6, 7, 8 or 9"
        if (d.all { it == d[0] }) return "That doesn't look like a real number"
        return null
    }

    fun e164(c: Country, national: String) = "+" + c.dial + national.filter { it.isDigit() }

    fun guess(ctx: android.content.Context): Country {
        val iso = try { (ctx.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).simCountryIso } catch (e: Exception) { "" }
        return all.firstOrNull { it.iso.equals(iso, true) } ?: all.firstOrNull { it.iso.equals(java.util.Locale.getDefault().country, true) } ?: all[0]
    }
}
