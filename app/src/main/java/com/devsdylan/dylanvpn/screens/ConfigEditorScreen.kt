package com.devsdylan.dylanvpn.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.devsdylan.dylanvpn.core.ConfigForm
import com.devsdylan.dylanvpn.core.FormFields
import com.devsdylan.dylanvpn.data.Store
import com.devsdylan.dylanvpn.ui.Pal

/** Profil à éditer (null = nouvelle config). */
object EditorHolder {
    var profileId: String? = null
}

@Composable
fun ConfigEditorScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val id = EditorHolder.profileId
    val existing = if (id == null) null else Store.profiles.value.firstOrNull { it.id == id }
    var fields by remember {
        mutableStateOf(
            try {
                if (existing != null) ConfigForm.fromLink(existing.link) else FormFields()
            } catch (e: IllegalArgumentException) {
                FormFields()
            }
        )
    }
    var error by remember { mutableStateOf("") }

    if (existing != null && existing.locked) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Config verrouillée : modification impossible.", color = Pal.Amber)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                try {
                    val link = ConfigForm.toLink(fields)
                    val problem = if (id == null) {
                        val result = Store.addProfiles(link)
                        if (result.added > 0) null else result.errors.firstOrNull() ?: "Config non ajoutée"
                    } else {
                        Store.updateProfile(id, link)
                    }
                    if (problem != null) {
                        error = problem
                    } else {
                        Toast.makeText(context, "Config enregistrée", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                } catch (e: IllegalArgumentException) {
                    error = e.message ?: "Formulaire incomplet"
                }
            }
        ) {
            Icon(Icons.Default.Done, contentDescription = null)
            Text("  Enregistrer")
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Pal.Red, modifier = Modifier.align(Alignment.Start))
        }
        Spacer(Modifier.height(14.dp))
        ConfigFormUi(fields) {
            fields = it
            error = ""
        }
        Spacer(Modifier.height(24.dp))
    }
}
