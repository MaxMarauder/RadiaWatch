import { BaseSideService } from '@zeppos/zml/base-side'

AppSideService(
    BaseSideService({
        onInit() {
            console.log('[RadiaWatch] companion onInit')
        },

        onRun() {},

        onDestroy() {},
    })
)
