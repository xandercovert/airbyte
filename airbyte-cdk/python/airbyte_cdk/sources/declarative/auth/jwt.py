#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#

from dataclasses import InitVar, dataclass
from datetime import datetime
from typing import Any, Mapping, Optional, Union

import jwt
from airbyte_cdk.sources.declarative.auth.declarative_authenticator import DeclarativeAuthenticator
from airbyte_cdk.sources.declarative.interpolation.interpolated_mapping import InterpolatedMapping
from airbyte_cdk.sources.declarative.interpolation.interpolated_string import InterpolatedString


@dataclass
class JwtAuthenticator(DeclarativeAuthenticator):

    config: Mapping[str, Any]
    parameters: InitVar[Mapping[str, Any]]
    secret_key: Union[InterpolatedString, str]
    algorithm: Union[InterpolatedString, str]
    token_duration: Union[InterpolatedString, str] = 1200
    kid: Union[InterpolatedString, str] = None
    typ: Union[InterpolatedString, str] = "JWT"
    iss: Union[InterpolatedString, str] = None
    sub: Union[InterpolatedString, str] = None
    aud: Union[InterpolatedString, str] = None
    cty: Union[InterpolatedString, str] = None
    additional_jwt_headers: Mapping[str, Any] = None
    additional_jwt_payload: Mapping[str, Any] = None

    def __post_init__(self, parameters: Mapping[str, Any]) -> None:
        super().__init__()
        self._algorithm = InterpolatedString.create(self.algorithm, parameters=parameters)
        self._secret_key = InterpolatedString.create(self.secret_key, parameters=parameters)
        self._kid = InterpolatedString.create(self.kid, parameters=parameters)
        self._typ = InterpolatedString.create(self.typ, parameters=parameters)
        self._iss = InterpolatedString.create(self.iss, parameters=parameters)
        self._sub = InterpolatedString.create(self.sub, parameters=parameters)
        self._aud = InterpolatedString.create(self.aud, parameters=parameters)
        self._cty = InterpolatedString.create(self.cty, parameters=parameters)
        self._token_duration = self.token_duration
        self._additional_jwt_headers = InterpolatedMapping(self.additional_jwt_headers or {}, parameters=parameters)
        self._additional_jwt_payload = InterpolatedMapping(self.additional_jwt_payload or {}, parameters=parameters)

    def _get_jwt_headers(self) -> Mapping[str, Any]:
        headers = {}
        headers.update(self._additional_jwt_headers.eval(self.config))
        if self._kid:
            headers["kid"] = f"{self._kid.eval(self.config)}"
        if self._algorithm:
            headers["alg"] = self._get_algorithm()
        if self._typ:
            headers["typ"] = f"{self._typ.eval(self.config)}"
        if self._cty:
            headers["cty"] = f"{self._cty.eval(self.config)}"
        return headers

    def _get_jwt_payload(self) -> Mapping[str, Any]:
        payload = {}
        now = int(datetime.now().timestamp())
        exp = now + self._token_duration
        nbf = now
        payload.update(self._additional_jwt_payload.eval(self.config))
        if self._iss:
            payload["iss"] = f"{self._iss.eval(self.config)}"
        if self._sub:
            payload["sub"] = f"{self._sub.eval(self.config)}"
        if self._aud:
            payload["aud"] = f"{self._aud.eval(self.config)}"
        payload["iat"] = now
        payload["exp"] = exp
        payload["nbf"] = nbf
        return payload

    def _get_algorithm(self) -> str:
        algorithm: str = self._algorithm.eval(self.config)
        if not algorithm:
            raise ValueError("Algorithm is required")
        return f"{algorithm}"

    def _get_secret_key(self) -> str:
        secret_key: str = self._secret_key.eval(self.config)
        if not secret_key:
            raise ValueError("secret_key is required")
        return f"{secret_key}"

    def _get_signed_token(self) -> str:
        return jwt.encode(
            payload=self._get_jwt_payload(),
            key=self._get_secret_key(),
            algorithm=self._get_algorithm(),
            headers=self._get_jwt_headers(),
        )

    @property
    def auth_header(self) -> str:
        return "Authorization"

    @property
    def token(self) -> str:
        return f"Bearer {self._get_signed_token()}"
