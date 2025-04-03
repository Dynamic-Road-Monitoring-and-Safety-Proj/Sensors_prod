package io.sensify.sensor.ui.pages

import android.view.animation.OvershootInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import io.sensify.sensor.R
import io.sensify.sensor.ui.navigation.NavDirectionsApp
import io.sensify.sensor.ui.resource.values.JlResColors
import io.sensify.sensor.ui.resource.values.JlResShapes
import io.sensify.sensor.ui.resource.values.JlResTxtStyles
import kotlinx.coroutines.delay


@Composable
fun SplashPage(navController: NavController) {
    // Create scale animations for logo and text
    val logoScaleAnimation = remember { Animatable(0.3f) }
    val eyeScaleAnimation = remember { Animatable(0.3f) }
    val textScaleAnimation = remember { Animatable(0.3f) }

    LaunchedEffect(key1 = true) {
        // Animate logo
        logoScaleAnimation.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 500,
                easing = {
                    OvershootInterpolator(2f).getInterpolation(it)
                }
            )
        )

        // Animate eye with slight delay
        eyeScaleAnimation.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 600,
                easing = {
                    OvershootInterpolator(3f).getInterpolation(it)
                }
            )
        )

        // Animate text with more delay
        textScaleAnimation.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 500,
                easing = {
                    OvershootInterpolator(1.5f).getInterpolation(it)
                }
            )
        )

        // Wait before navigating
        delay(timeMillis = 800)

        navController.popBackStack()
        navController.navigate(NavDirectionsApp.HomePage.route)
    }

    SplashScreen(
        logoScaleAnimation = logoScaleAnimation,
        eyeScaleAnimation = eyeScaleAnimation,
        textScaleAnimation = textScaleAnimation
    )
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    logoScaleAnimation: Animatable<Float, AnimationVector1D>,
    eyeScaleAnimation: Animatable<Float, AnimationVector1D>,
    textScaleAnimation: Animatable<Float, AnimationVector1D>
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f),
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.pic_logo),
                contentDescription = "Logotipo Splash Screen",
                modifier = modifier
                    .size(120.dp)
                    .scale(scale = logoScaleAnimation.value),
            )
            Spacer(modifier = JlResShapes.Space.H24)
            Image(
                painter = painterResource(id = R.drawable.pic_launcher_eye),
                contentDescription = "Logotipo Splash Screen",
                modifier = modifier
                    .size(220.dp)
                    .scale(scale = eyeScaleAnimation.value),
            )
            Spacer(modifier = JlResShapes.Space.H56)
            Text(
                text = "Sensify",
                style = JlResTxtStyles.h1.merge(
                    other = TextStyle(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.onSurface,
                                MaterialTheme.colorScheme.onSurface.copy(0.1f)
                            ),
                            tileMode = TileMode.Mirror,
                            start = Offset(0f, 0f),
                            end = Offset(0f, Float.POSITIVE_INFINITY),
                        )
                    )
                ),
                modifier = Modifier.scale(scale = textScaleAnimation.value)
            )
        }
    }
}
