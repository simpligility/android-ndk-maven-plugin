package com.simpligility.maven.plugins.androidndk.phase00clean;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.io.File;

/**
 * @author Johan Lindquist <johanlindquist@gmail.com>
 * @goal clean
 * @requiresProject true
 * @requiresOnline false
 * @phase clean
 */
public class NdkCleanMojo extends AbstractMojo
{

    /**
     * @parameter property="android.nativeBuildLibsOutputDirectory" default-value="${project.basedir}/libs"
     */
    File ndkBuildLibsOutputDirectory;

    /**
     * @parameter property="android.nativeBuildObjOutputDirectory" default-value="${project.basedir}/obj"
     */
    File ndkBuildObjOutputDirectory;

    /**
     * Forces the clean process to be skipped.
     *
     * @parameter property="android.nativeBuildSkipClean" default-value="false"
     */
    boolean skipClean = false;

    /**
     * Specifies whether the deletion of the libs/ folder structure should be skipped.  This is by default set to
     * skip (true) to avoid unwanted deletions of libraries already present in this structure.
     *
     * @parameter property="android.nativeBuildSkipCleanLibsOutputDirectory" default-value="true"
     */
    boolean skipBuildLibsOutputDirectory = true;

    /**
     * Specifies whether the obj/ build folder structure should be deleted.
     *
     * @parameter property="android.nativeBuildSkipCleanLibsOutputDirectory" default-value="false"
     */
    boolean skipBuildObjsOutputDirectory = false;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {

    }

}
