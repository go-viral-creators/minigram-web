package com.kafisir.wetalks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kafisir.wetalks.data.Countries
import com.kafisir.wetalks.data.Country

/** Country-code picker + digits-only number box with per-country length validation. */
@Composable
fun PhoneField(country: Country, onCountry: (Country) -> Unit, number: String, onNumber: (String) -> Unit, label: String = "Phone number", error: String? = null, hint: Boolean = true) {
    var pick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = We.text2, style = T.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).background(We.field).border(1.dp, if (error != null) We.danger else We.line, RoundedCornerShape(12.dp)), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.fillMaxHeight().clickable { pick = true }.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(country.flag, style = T.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Spacer(Modifier.width(6.dp)); Text("+" + country.dial, color = We.text, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                Spacer(Modifier.width(2.dp)); Icon(Ic.Chevron, We.text2, 14.dp, Modifier.then(Modifier))
            }
            Box(Modifier.width(1.dp).height(26.dp).background(We.line))
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (number.isEmpty()) Text("•".repeat(country.min), color = We.text2.copy(alpha = .5f), style = T.body)
                BasicTextField(number, { onNumber(Countries.clean(country, it)) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text),
                    cursorBrush = SolidColor(We.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            }
            if (number.isNotEmpty()) Text("${number.length}/${country.max}", color = if (error == null) We.text2 else We.danger, style = T.tiny, modifier = Modifier.padding(end = 12.dp))
        }
        if (error != null) Text(error, color = We.danger, style = T.small, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
        else if (hint) Text(if (country.min == country.max) "${country.min} digits, without the country code" else "${country.min}-${country.max} digits, without the country code", color = We.text2, style = T.tiny, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
    }
    if (pick) Sheet({ pick = false }) {
        var q by remember { mutableStateOf("") }
        Text("Select country", color = We.text, style = T.title, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(We.field).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Search, We.text2, 18.dp); Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search country or code", color = We.text2, style = T.body)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = T.body.copy(color = We.text), cursorBrush = SolidColor(We.primary))
            }
        }
        val list = Countries.all.filter { q.isBlank() || it.name.contains(q, true) || it.dial.startsWith(q.trim().removePrefix("+")) || it.iso.equals(q.trim(), true) }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(list, key = { it.iso }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onCountry(c); pick = false }.padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.flag, style = T.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp))); Spacer(Modifier.width(14.dp))
                    Text(c.name, color = We.text, style = T.body, modifier = Modifier.weight(1f))
                    Text("+" + c.dial, color = We.text2, style = T.body)
                    if (c.iso == country.iso) { Spacer(Modifier.width(10.dp)); Icon(Ic.Check, We.primary, 18.dp) }
                }
            }
        }
    }
}
