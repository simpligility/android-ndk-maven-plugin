package com.simpligility.maven.plugins.androidndk.configuration;

/**
 * @author Johan Lindquist
 */
public class IgnoreHeaderFilesArchive
{
    private String groupId;
    private String artifactId;

    public String getArtifactId ()
    {
        return artifactId;
    }

    public void setArtifactId ( final String artifactId )
    {
        this.artifactId = artifactId;
    }

    public String getGroupId ()
    {
        return groupId;
    }

    public void setGroupId ( final String groupId )
    {
        this.groupId = groupId;
    }
}
