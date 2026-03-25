package io.spicelabs.goatrodeo.omnibor

object MetadataKeyConstants {
  def NAME = "Name"
  def SIMPLE_NAME = "SimpleName"
  def VERSION = "Version"
  def LOCALE = "Locale"
  def PUBLIC_KEY = "PublicKey"
  def PUBLISHER = "Publisher"
  def PUBLICATION_DATE = "PublicationDate"
  def COPYRIGHT = "Copyright"
  def DESCRIPTION = "Description"
  def TRADEMARK = "Trademark"
  def ARTIFACTID = "ArtifactID"
  def LICENSE = "License"
  def DEPENDENCIES = "Dependencies"
  def URL = "Url"

  /** Function to generate a prefix for an ad hoc key. This is intended to be
    * used for keys that are not defined as above and to be able to identify
    * them as associated with a particular producer. This is intented to be used
    * as partial application onto your prefix.
    *
    * @param prefix
    *   the prefix to prepend
    * @param key
    *   the metdata key
    * @return
    *   the resulting final key in the form "prefix:key"
    */
  def adHoc(prefix: String)(key: String) = s"$prefix:$key"
}
