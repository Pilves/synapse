package com.synapse.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A reusable composable for onboarding pages.
 *
 * This component provides a consistent layout for each page in the onboarding flow,
 * including an icon, title, description, and action buttons.
 *
 * @param icon The emoji or icon character to display prominently
 * @param title The main title of the page
 * @param description The descriptive text explaining this step
 * @param primaryButtonText Text for the primary action button
 * @param onPrimaryClick Callback when the primary button is clicked
 * @param secondaryButtonText Optional text for a secondary action button
 * @param onSecondaryClick Optional callback for the secondary button
 * @param tertiaryButtonText Optional text for a tertiary (skip) button
 * @param onTertiaryClick Optional callback for the tertiary button
 * @param isLoading Whether to show a loading indicator on the primary button
 * @param isPrimaryEnabled Whether the primary button is enabled
 * @param modifier Modifier for the root composable
 */
@Composable
fun OnboardingPage(
    icon: String,
    title: String,
    description: String,
    primaryButtonText: String,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryButtonText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    tertiaryButtonText: String? = null,
    onTertiaryClick: (() -> Unit)? = null,
    isLoading: Boolean = false,
    isPrimaryEnabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        AnimatedVisibility(
            visible = true,
            enter = fadeIn() + slideInVertically { -40 }
        ) {
            Text(
                text = icon,
                fontSize = 72.sp,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Description
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        // Primary and Secondary buttons in a row (if secondary exists and is short)
        if (secondaryButtonText != null && onSecondaryClick != null && secondaryButtonText.length < 15) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                OutlinedButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(text = secondaryButtonText)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = onPrimaryClick,
                    modifier = Modifier.weight(1f),
                    enabled = isPrimaryEnabled && !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = primaryButtonText)
                    }
                }
            }
        } else {
            // Stack buttons vertically
            Button(
                onClick = onPrimaryClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = isPrimaryEnabled && !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = primaryButtonText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (secondaryButtonText != null && onSecondaryClick != null) {
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isLoading
                ) {
                    Text(
                        text = secondaryButtonText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // Tertiary button (Skip for now)
        AnimatedVisibility(
            visible = tertiaryButtonText != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (tertiaryButtonText != null && onTertiaryClick != null) {
                TextButton(
                    onClick = onTertiaryClick,
                    modifier = Modifier.padding(top = 16.dp),
                    enabled = !isLoading
                ) {
                    Text(
                        text = tertiaryButtonText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Data class representing the content for an onboarding page.
 */
data class OnboardingPageContent(
    val icon: String,
    val title: String,
    val description: String,
    val primaryButtonText: String,
    val secondaryButtonText: String? = null,
    val tertiaryButtonText: String? = null
)
