# R8 shrinks the release build; these keep what it can't see being used.

# Names stay readable, so stack traces in shared logs need no mapping file. Shrinking does
# the real work on size.
-dontobfuscate

# Gearslip hosts other apps' car screens: their templates arrive as Bundles, and the car
# library rebuilds them by class name and reflection. Any model class an app might send has to
# survive, used by Gearslip's code or not.
-keep class androidx.car.app.** { *; }

# Speech-to-text: the native library calls back into these classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Shizuku starts the shell service in its own process by class name, with no caller R8 can see.
-keep class app.seb3thehacker.gearslip.shizuku.ShellService { <init>(...); *; }
