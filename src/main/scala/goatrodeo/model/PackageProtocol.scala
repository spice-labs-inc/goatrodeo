package goatrodeo.model

enum PackageProtocol {
  case Maven, NPM, Docker, Deb, Gem, RPM, Unsupported

  def name: String = {
    this match {
      case Maven  => "maven"
      case NPM    => "npm"
      case Docker => "docker"
      case Deb    => "deb"
      case Gem    => "gem"
      case RPM    => "rpm"
      case Unsupported => "unsupported package format" // todo
    }
  }
}
