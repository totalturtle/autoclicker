# AccessibilityService는 시스템이 리플렉션으로 참조하므로 난독화 제외
-keep class com.autoclicker.AutoClickAccessibilityService { *; }
-keep class com.autoclicker.OverlayService { *; }
-keep class com.autoclicker.ClickConfig { *; }
