import { BaseApp } from '@zeppos/zml/base-app'
import { log } from '@zos/utils'

App(
    BaseApp({
        globalData: {},
        onCreate(options) {
            console.log('App onCreate')
            this._patchTransport()
        },

        _patchTransport() {
            const transport = this.globalData.messaging?.transport
            if (!transport) return

            // Silence high-frequency BLE debug logs (shake send/success, request/response IDs).
            // The 'device-message' logger is a singleton; overriding .info here affects the
            // same instance used by the transport in zml-app.js.
            const deviceLog = log.getLogger('device-message')
            deviceLog.info = () => {}

            // Fix memory leak: sessions hold a reference cycle via EventBus listeners that
            // close over the session object (session → EventBus → listener → closure → session).
            // On the watch's reference-counting JS runtime this cycle prevents GC.
            //
            // We can't call off() synchronously inside destroy() because destroy() is invoked
            // from within the "data" event handler — calling off("data") re-entrantly while
            // the native EventBus is mid-dispatch is a no-op on this runtime.
            // Deferring to a fresh call stack via setTimeout(0) avoids the re-entrancy.
            const mgr = transport.sessionMgr
            const origDestroy = mgr.destroy.bind(mgr)
            mgr.destroy = function(session) {
                origDestroy(session)
                setTimeout(() => {
                    try { session.off('data') } catch (_) {}
                    try { session.off('error') } catch (_) {}
                }, 0)
            }
        },

        onDestroy(options) {
            console.log('App onDestroy')
        },
    })
)
