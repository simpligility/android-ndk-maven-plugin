package com.simpligility.maven.plugins.androidndk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.simpligility.maven.plugins.androidndk.PluginInfo;

public class PluginInfoTest {
  
  @Test
  public void confirmGroupId()
  {
    assertEquals( "com.simpligility.maven.plugins", PluginInfo.getGroupId() );
  }

  @Test
  public void confirmArtifactId()
  {
    assertEquals( "android-ndk-maven-plugin", PluginInfo.getArtifactId() );
  }

  @Test
  public void confirmVersion()
  {
    assertNotNull( PluginInfo.getVersion() );
  }

  @Test
  public void confirmGav()
  {
    assertTrue( PluginInfo.getGAV()
        .startsWith( "com.simpligility.maven.plugins:android-ndk-maven-plugin:" ) );
  }
}
