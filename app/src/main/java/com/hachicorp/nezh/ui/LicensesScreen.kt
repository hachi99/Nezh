package com.hachicorp.nezh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.hachicorp.nezh.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Last row of the folder list: opens the license notice. It is not a folder. */
@Composable
internal fun LicensesNoticeEntry(onClick: () -> Unit) {
    Column {
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.licenses_title)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
            colors = ListItemDefaults.colors(
                headlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        )
    }
}

/**
 * Third-party components with their holders, licenses and copyright notices; each full license
 * text (and the library lists) opens on the same screen. Back returns from a text to the list,
 * and from the list to the library.
 */
@Composable
internal fun LicensesScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var openAsset by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = openAsset != null) { openAsset = null }
    Surface(modifier = modifier.fillMaxSize()) {
        Column {
            val document = openAsset?.let { asset -> documentTitles().getValue(asset) to asset }
            TopBar(
                title = document?.first ?: stringResource(R.string.licenses_title),
                onBack = if (document != null) ({ openAsset = null }) else onClose,
            )
            if (document == null) {
                ComponentList(onOpen = { openAsset = it })
            } else {
                AssetText(asset = document.second)
            }
        }
    }
}

/** Title of every document the screen can open, by asset. */
private fun documentTitles(): Map<String, String> =
    LicenseText.entries.associate { it.asset to it.title } +
        thirdPartyComponents.mapNotNull { c -> c.librariesAsset?.let { it to c.name } }

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_chevron_left), stringResource(R.string.action_back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun ComponentList(onOpen: (asset: String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(thirdPartyComponents, key = { it.name }) { component ->
            HorizontalDivider()
            ComponentNotice(component, onOpen)
        }
    }
}

@Composable
private fun ComponentNotice(component: ThirdPartyComponent, onOpen: (asset: String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = component.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        if (component.version.isNotEmpty()) {
            Text(stringResource(R.string.licenses_version, component.version), style = MaterialTheme.typography.bodyMedium, color = secondary)
        }
        Text(
            text = component.holder?.let { stringResource(R.string.licenses_holder, it) } ?: stringResource(R.string.licenses_holder_undeclared),
            style = MaterialTheme.typography.bodyMedium,
            color = secondary,
        )
        Text(stringResource(R.string.licenses_license, component.licenseName), style = MaterialTheme.typography.bodyMedium, color = secondary)
        component.copyright.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        component.description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        component.links.forEach { LinkText(it) }
        Row {
            // The visible labels repeat on every component: the spoken ones name it.
            val textLabel = stringResource(R.string.licenses_show_text_of, component.name)
            TextButton(onClick = { onOpen(component.license.asset) }, modifier = Modifier.semantics { contentDescription = textLabel }) {
                Text(stringResource(R.string.licenses_show_text))
            }
            component.librariesAsset?.let { asset ->
                val librariesLabel = stringResource(R.string.licenses_show_libraries_of, component.name)
                TextButton(onClick = { onOpen(asset) }, modifier = Modifier.semantics { contentDescription = librariesLabel }) {
                    Text(stringResource(R.string.licenses_show_libraries))
                }
            }
        }
    }
}

@Composable
private fun LinkText(url: String) {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Text(
        text = buildAnnotatedString { withLink(LinkAnnotation.Url(url, style)) { append(url) } },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A text from the assets, verbatim, scrollable and selectable. */
@Composable
private fun AssetText(asset: String) {
    val context = LocalContext.current
    val text by produceState<Result<String>?>(initialValue = null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }
        }
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        text?.let { result ->
            SelectionContainer {
                Text(
                    text = result.getOrElse { stringResource(R.string.licenses_load_error) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
