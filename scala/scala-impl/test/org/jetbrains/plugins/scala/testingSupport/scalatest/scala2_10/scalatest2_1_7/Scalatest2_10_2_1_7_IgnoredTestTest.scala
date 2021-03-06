package org.jetbrains.plugins.scala.testingSupport.scalatest.scala2_10.scalatest2_1_7

import org.jetbrains.plugins.scala.SlowTests
import org.jetbrains.plugins.scala.testingSupport.scalatest.IgnoredSpecTest
import org.junit.experimental.categories.Category

/**
 * @author Roman.Shein
 * @since 22.01.2015.
 */
@Category(Array(classOf[SlowTests]))
class Scalatest2_10_2_1_7_IgnoredTestTest extends Scalatest2_10_2_1_7_Base with IgnoredSpecTest
