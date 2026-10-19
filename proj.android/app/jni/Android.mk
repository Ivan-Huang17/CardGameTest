LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := MyGame_shared

LOCAL_MODULE_FILENAME := libMyGame

LOCAL_SRC_FILES := $(LOCAL_PATH)/hellocpp/main.cpp \
                   $(LOCAL_PATH)/../../../Classes/AppDelegate.cpp \
                   $(LOCAL_PATH)/../../../Classes/configs/LevelConfig.cpp \
                   $(LOCAL_PATH)/../../../Classes/controllers/GameController.cpp \
                   $(LOCAL_PATH)/../../../Classes/controllers/PlayFiledController.cpp \
                   $(LOCAL_PATH)/../../../Classes/controllers/StackController.cpp \
                   $(LOCAL_PATH)/../../../Classes/models/CardModel.cpp \
                   $(LOCAL_PATH)/../../../Classes/models/GameModel.cpp \
                   $(LOCAL_PATH)/../../../Classes/views/CardView.cpp \
                   $(LOCAL_PATH)/../../../Classes/views/GameView.cpp \
                   $(LOCAL_PATH)/../../../Classes/views/PlayFieldView.cpp \
                   $(LOCAL_PATH)/../../../Classes/views/StackView.cpp

LOCAL_C_INCLUDES := $(LOCAL_PATH)/../../../Classes \
                    $(LOCAL_PATH)/../../../Classes/configs \
                    $(LOCAL_PATH)/../../../Classes/controllers \
                    $(LOCAL_PATH)/../../../Classes/models \
                    $(LOCAL_PATH)/../../../Classes/views

# _COCOS_HEADER_ANDROID_BEGIN
# _COCOS_HEADER_ANDROID_END


LOCAL_STATIC_LIBRARIES := cc_static

# _COCOS_LIB_ANDROID_BEGIN
# _COCOS_LIB_ANDROID_END

include $(BUILD_SHARED_LIBRARY)

$(call import-module, cocos)

# _COCOS_LIB_IMPORT_ANDROID_BEGIN
# _COCOS_LIB_IMPORT_ANDROID_END
