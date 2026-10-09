package bj.phonepilote.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bj.phonepilote.app.R

/*
 * Glassmorphisme + flat design, sans bibliothèque :
 *  - le fond porte de grandes taches de couleur très douces (dégradés radiaux bleu / jaune) ;
 *  - les cartes sont du blanc translucide avec un liseré clair, ce qui laisse deviner les taches derrière.
 * Les taches étant déjà floues par construction, le rendu est identique de Android 8 à 15
 * (le vrai flou d'arrière-plan n'existe qu'à partir d'Android 12 et coûte cher sur les petits téléphones).
 */

/** Fond de l'app : couleur claire + 3 halos (bleu en haut, jaune à droite, bleu en bas). */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(PP.Bg)
            .drawBehind {
                val w = size.width
                val h = size.height
                fun blob(c: Color, x: Float, y: Float, r: Float) = drawCircle(
                    Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), Offset(x, y), r),
                    radius = r, center = Offset(x, y),
                )
                blob(PP.Blue.copy(alpha = 0.30f), w * 0.05f, h * 0.02f, w * 0.95f)
                blob(PP.Yellow.copy(alpha = 0.45f), w * 1.05f, h * 0.34f, w * 0.75f)
                blob(PP.Blue.copy(alpha = 0.18f), w * 0.15f, h * 0.92f, w * 0.9f)
            },
        content = content,
    )
}

private val glassBorder = Brush.verticalGradient(listOf(PP.GlassEdge, PP.GlassEdgeFade))

/** Carte en verre dépoli. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strong: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (strong) listOf(PP.GlassStrong, PP.Glass) else listOf(PP.Glass, Color(0x80FFFFFF))
                )
            )
            .border(1.dp, glassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Pastille d'icône : carré arrondi plein (flat). */
@Composable
fun IconTile(icon: ImageVector, tint: Color, background: Color, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(background),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(iconSize), tint = tint) }
}

@Composable
fun Logo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo), "PhonePilote", modifier.size(size))
}

// ---------------------------------------------------------------- Boutons

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PP.Blue, contentColor = Color.White,
            disabledContainerColor = PP.Blue.copy(alpha = 0.35f), disabledContentColor = Color.White,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        } else {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Bouton jaune (accent) : réservé à l'action la plus importante d'un écran. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PP.Yellow, contentColor = PP.Ink),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = PP.Blue) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PP.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.6f), contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---------------------------------------------------------------- Champs

@Composable
fun PField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    password: Boolean = false,
    supporting: String? = null,
    leading: ImageVector? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboard,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        supportingText = supporting?.let { { Text(it, color = PP.Muted, fontSize = 12.sp) } },
        leadingIcon = leading?.let { { Icon(it, null, tint = PP.Muted) } },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PP.Blue,
            unfocusedBorderColor = PP.Line,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White.copy(alpha = 0.7f),
            focusedLabelColor = PP.Blue,
            unfocusedLabelColor = PP.Muted,
            cursorColor = PP.Blue,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Bottom sheets

/**
 * Bottom sheet maison : verre très clair, coins de 32 dp, halo de marque en haut, poignée discrète.
 * `locked` = impossible à fermer (onboarding, mise à jour obligatoire).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PSheet(
    onDismiss: () -> Unit,
    locked: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !locked || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!locked) onDismiss() },
        sheetState = state,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = Color(0xF7FFFFFF),
        contentColor = PP.Ink,
        scrimColor = Color(0x800B1B3A),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier.padding(top = 12.dp, bottom = 4.dp).size(40.dp, 5.dp)
                    .clip(CircleShape).background(if (locked) Color.Transparent else PP.Line)
            )
        },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !locked),
    ) {
        Column(
            Modifier.fillMaxWidth().drawBehind {
                // Halo de marque derrière l'en-tête de la feuille.
                drawCircle(
                    Brush.radialGradient(
                        listOf(PP.Blue.copy(alpha = 0.10f), Color.Transparent),
                        Offset(size.width * 0.15f, 0f), size.width * 0.7f,
                    ),
                    radius = size.width * 0.7f, center = Offset(size.width * 0.15f, 0f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(PP.Yellow.copy(alpha = 0.22f), Color.Transparent),
                        Offset(size.width, size.width * 0.1f), size.width * 0.5f,
                    ),
                    radius = size.width * 0.5f, center = Offset(size.width, size.width * 0.1f),
                )
            },
            content = content,
        )
    }
}

/** En-tête standard d'une feuille : pastille d'icône, titre, sous-titre. */
@Composable
fun SheetHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = PP.Blue,
    tile: Color = PP.BlueSoft,
    centered: Boolean = false,
) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        if (icon != null) {
            IconTile(icon, tint, tile, size = 56.dp, iconSize = 28.dp)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            title, style = MaterialTheme.typography.headlineSmall, color = PP.Ink,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.typography.bodyLarge, color = PP.Muted,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Indicateur d'étape « ● ● ○ » pour l'onboarding. */
@Composable
fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == current) 22.dp else 6.dp).clip(CircleShape)
                    .background(if (i <= current) PP.Blue else PP.Line)
            )
        }
    }
}

/** Petite étiquette d'état (flat). */
@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
