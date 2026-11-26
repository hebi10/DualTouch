# DT-DES-07-R2 — Return from actual Android accessibility Settings

R1 CI 45542145885: build/test and ordinary screenshot jobs passed; overlay capture failed at permission flow / show Thumb: Timeout: connected permission on home. The diagnostic report and actual screenshot show that onboarding scroll, service-list navigation, explicit system confirmation and the service-enabled setting succeeded. Android Settings remained foreground with the service switch on. No actual split approval or overlay state was captured.

This correction is limited to the isolated probe-left androidTest runner. Verify the active application package through actual accessibility windows; use bounded Android Back navigation only while Settings is foreground. If needed, leave Settings via Home and relaunch MainActivity with NEW_TASK/CLEAR_TOP without force-stopping the app. Confirm the controller is foreground and its actual home reports the connected service before showing Thumb. Recheck the service setting; record the navigation and permission-binding observations. Do not inject app preferences, directly enable a service, approve a fake window pair or change production code.

DES-04 remains separate because opening the controller can invalidate a real approved pair. New CI and screenshot evidence are required. No physical device QA, Play publishing or readiness changes.
