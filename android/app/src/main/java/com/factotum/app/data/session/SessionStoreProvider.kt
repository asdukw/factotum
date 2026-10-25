package com.factotum.app.data.session

import android.content.Context
import com.factotum.app.FactotumApplication

/** 从任意 Context 取到应用级的会话库。 */
fun Context.factotumSessionStore(): SessionStore =
  (applicationContext as FactotumApplication).sessionStore
