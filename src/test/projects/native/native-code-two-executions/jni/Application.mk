ifeq ($(ENABLE_DEBUG),1)
    APP_OPTIM := debug
    APP_CFLAGS := -g3 -O0
    APP_CPPFLAGS := -g3 -O0
else
    APP_OPTIM := release
    APP_CFLAGS := -g0 -DNDEBUG -fvisibility=hidden
    APP_CPPFLAGS := -g0 -DNDEBUG -fvisibility=hidden
endif