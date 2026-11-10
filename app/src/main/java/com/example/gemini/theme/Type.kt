package com.example.gemini.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography =
  Typography(
    displayLarge = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Bold,
      fontSize = 34.sp,
      lineHeight = 40.sp,
      letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Bold,
      fontSize = 26.sp,
      lineHeight = 32.sp,
      letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.SemiBold,
      fontSize = 22.sp,
      lineHeight = 28.sp,
      letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.SemiBold,
      fontSize = 19.sp,
      lineHeight = 25.sp,
      letterSpacing = (-0.15).sp
    ),
    titleMedium = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.SemiBold,
      fontSize = 16.sp,
      lineHeight = 22.sp,
      letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Medium,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.05.sp
    ),
    bodyLarge = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Normal,
      fontSize = 15.5.sp,
      lineHeight = 23.sp,
      letterSpacing = 0.1.sp
    ),
    bodyMedium = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Normal,
      fontSize = 14.sp,
      lineHeight = 20.sp,
      letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Normal,
      fontSize = 12.5.sp,
      lineHeight = 17.sp,
      letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.SemiBold,
      fontSize = 13.5.sp,
      lineHeight = 18.sp,
      letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Medium,
      fontSize = 12.sp,
      lineHeight = 16.sp,
      letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
      fontFamily = FontFamily.Default,
      fontWeight = FontWeight.Medium,
      fontSize = 11.sp,
      lineHeight = 15.sp,
      letterSpacing = 0.25.sp
    )
  )
