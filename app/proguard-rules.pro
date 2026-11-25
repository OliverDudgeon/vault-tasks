# Keep Java-serialized task index classes and their fields intact (Store.kt).
-keep class com.vaulttasks.IndexedNote { *; }
-keep class com.vaulttasks.Task { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep enum names persisted in SharedPreferences (Store.kt, Filter.kt).
-keepclassmembers enum com.vaulttasks.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
