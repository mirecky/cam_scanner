# PdfBox-Android: voliteľné závislosti, ktoré aplikácia nepoužíva (JPEG 2000, šifrovanie).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# Tesseract4Android: triedy volané z natívneho kódu (JNI).
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
