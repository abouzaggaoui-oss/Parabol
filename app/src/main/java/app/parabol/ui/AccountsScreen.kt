package app.parabol.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parabol.MainViewModel
import app.parabol.NightBg
import app.parabol.R
import app.parabol.data.Profile

private val AvatarColors = listOf(
    Color(0xFFF5B841), Color(0xFF9C8CFF), Color(0xFF4FD1C5), Color(0xFFFF7A6B),
    Color(0xFF6BB8FF), Color(0xFF8BD17C), Color(0xFFE58BD8), Color(0xFFFFA45C)
)

/**
 * Shown before Home when there is more than one account: pick the account to open.
 * Opening one goes to Home; Home's back button comes back here.
 */
@Composable
fun AccountsScreen(vm: MainViewModel, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val profiles = vm.profiles
    val firstTile = rememberFirstItemFocus(ready = profiles.isNotEmpty())

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NightBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFF5B841).copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 900f
                )
            )
    ) {
        val wide = maxWidth >= 600.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = if (wide) 48.dp else 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.width(12.dp))
                Text("Parabol", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(if (wide) 40.dp else 28.dp))
            Text(
                stringResource(R.string.accounts_title),
                fontSize = if (wide) 34.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.accounts_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(if (wide) 24.dp else 12.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 190.dp else 140.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                // Room for the focused tile's ring and scale-up, so it is never clipped.
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                modifier = Modifier.widthIn(max = 1000.dp)
            ) {
                itemsIndexed(profiles, key = { _, p -> p.id }) { index, profile ->
                    AccountTile(
                        profile = profile,
                        color = AvatarColors[index % AvatarColors.size],
                        isLast = profile.id == vm.activeProfile?.id,
                        focusRequester = if (index == 0) firstTile else null,
                        onClick = { onOpen(profile.id) }
                    )
                }
                item(key = "add") { AddTile(onAdd) }
            }
        }
    }
}

@Composable
private fun AccountTile(
    profile: Profile,
    color: Color,
    isLast: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .tvClickable(cornerRadius = 20, focusRequester = focusRequester, onClick = onClick)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .padding(vertical = 22.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(84.dp)
                .background(color.copy(alpha = 0.22f), CircleShape)
                .border(2.dp, color.copy(alpha = if (isLast) 0.9f else 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                profile.name.trim().firstOrNull()?.uppercase() ?: "?",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            profile.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AddTile(onAdd: () -> Unit) {
    Column(
        Modifier
            .tvClickable(cornerRadius = 20, onClick = onAdd)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(vertical = 22.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(84.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.account_add_tile),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
