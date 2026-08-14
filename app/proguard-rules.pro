# Root mode's privileged classes are never referenced by name from app code that R8 can see: libsu
# spawns a root process and loads RootUserService reflectively by class name, which then hands back
# a UserService over the IUserService AIDL. Shrinking or renaming any of them breaks root mode at
# runtime with nothing at build time to warn about it.
#
# Release currently sets `optimization { enable = false }`, so none of this is live yet — it is here
# so that turning optimization on later doesn't quietly take root mode out.
-keep class com.volume_plus_plus.app.service.RootUserService { *; }
-keep class com.volume_plus_plus.app.service.UserService { *; }
-keep class com.volume_plus_plus.app.IUserService { *; }
-keep class com.volume_plus_plus.app.IUserService$* { *; }

# libsu's own root-side entry points are likewise reached reflectively from the spawned process.
-keep class com.topjohnwu.superuser.internal.** { *; }
