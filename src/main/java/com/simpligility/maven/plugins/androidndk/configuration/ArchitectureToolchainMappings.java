package com.simpligility.maven.plugins.androidndk.configuration;

import com.simpligility.maven.plugins.androidndk.AndroidNdk;

/**
 * @author
 */
public class ArchitectureToolchainMappings
{
    String x8664 = AndroidNdk.X86_TOOLCHAIN[0];
    String x86 = AndroidNdk.X86_64_TOOLCHAIN[0];
    String armeabi = AndroidNdk.ARM_TOOLCHAIN[0];
    String arm64 = AndroidNdk.ARM_64_TOOLCHAIN[0];
    String mips = AndroidNdk.ARM_TOOLCHAIN[0];

    public String getArmeabi()
    {
        return armeabi;
    }

    public void setArmeabi( final String armeabi )
    {
        this.armeabi = armeabi;
    }

    public String getMips()
    {
        return mips;
    }

    public void setMips( final String mips )
    {
        this.mips = mips;
    }

    public String getX86()
    {
        return x86;
    }

    public void setX86( final String x86 )
    {
        this.x86 = x86;
    }

    public String getArm64()
    {
        return arm64;
    }

    public void setArm64( final String arm64 )
    {
        this.arm64 = arm64;
    }

    public String getX8664 ()
    {
        return x8664;
    }

    public void setX8664 ( final String x8664 )
    {
        this.x8664 = x8664;
    }
}
