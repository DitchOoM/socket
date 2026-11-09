# The app keeps nothing of the library by hand: what R8 keeps is what the libraries' consumer rules and
# AGP's default file say, which is the configuration a real app ships.
-dontwarn java.lang.management.**
-dontwarn kotlinx.coroutines.debug.**
