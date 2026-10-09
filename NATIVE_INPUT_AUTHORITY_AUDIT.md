# NATIVE INPUT AUTHORITY AUDIT

## EXECUTIVE SUMMARY

- PROVEN:
  - `nativeInjectStrobePackets()` is a coordinate calculator, not a direct Linux kernel touch driver injector.
  - On non-rooted Android devices, `AccessibilityService.dispatchGesture()` is the single authoritative gesture execution boundary.
  - Previous `nativeInjectTap` and `nativeInjectSwipe` returned `JNI_TRUE` without injecting input, causing gesture execution to be silently dropped.

---

## INPUT PATH ARCHITECTURE

```
Engine / Contributor
   ↓
Arbitration / ConAI
   ↓
ExecutionRequest
   ↓
CentralExecutionBus
   ↓
SmartAssistAccessibilityEngine / AsynchronousGestureQueue
   ↓
GestureExecutionAuthority
   ↓
AccessibilityService.dispatchGesture()
   ↓
Android OS Input Dispatcher
   ↓
eFootball 2027
```

---

## AUDIT OF DIRECT & LEGACY INPUT PATHS

1. `AsynchronousGestureQueue.enqueueGesture()`
   - Path: `NativeBridge.nativeCompileMotionEvent` -> `nativeInjectStrobePackets` -> `GestureExecutionAuthority.execute`
   - Classification: ACTIVE
   - Mutation: Consumes calculated `strobePackets` to construct multi-stroke `GestureDescription` for high-frequency input dispatch.

2. `NativeInputBridge.injectTap` / `injectSwipe`
   - Path: `nativeInjectTap` / `nativeInjectSwipe` -> Fallback to `GestureExecutionAuthority.execute`
   - Classification: ACTIVE
   - Mutation: Native call returns `JNI_FALSE` (non-root fast check), ensuring fallback to `GestureExecutionAuthority` executes the gesture.

3. `ActiveGestureController.injectWinningVector`
   - Path: `GestureExecutionAuthority.execute`
   - Classification: SUPERVISORY / ACTIVE
   - Reason: Standard fallback path for direct vector injection.

4. `SmartAssistAccessibilityEngine.executeDirectRequest`
   - Path: `CentralExecutionBus` -> `SmartAssistAccessibilityEngine` -> `GestureExecutionAuthority.execute`
   - Classification: ACTIVE AUTHORITATIVE BUS CONSUMER

---

## ACCESSIBILITY CAPABILITY VERIFICATION

- Service Configuration: `app/src/main/res/xml/accessibility_service_config.xml`
- XML Namespace: `http://schemas.android.com/apk/res/android`
- Declared Capability: `android:canPerformGestures="true"`
- Capability verified in APK build manifest metadata.
