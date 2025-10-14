class WkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WkLogSystemBridge.init(this)
    }
}
