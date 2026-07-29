package io.github.we.lite.loader.abc

interface IClassLoaderHelper {

    fun createEmptyInMemoryMultiDexClassLoader(parent: ClassLoader): ClassLoader

    fun injectDexToClassLoader(classLoader: ClassLoader, dexBytes: ByteArray, dexName: String?)
}
