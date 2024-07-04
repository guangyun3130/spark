from typing import cast, Type, TYPE_CHECKING

import pyspark.sql.connect.proto as pb2
from pyspark.ml.remote.serialize import serialize_ml_params, deserialize
from pyspark.ml.util import MLWriter, MLReader, RL
from pyspark.sql.connect.expressions import LiteralExpression

if TYPE_CHECKING:
    from pyspark.ml.util import JavaMLReadable, JavaMLWritable
    from pyspark.core.context import SparkContext


class RemoteMLWriter(MLWriter):
    def __init__(self, instance: "JavaMLWritable") -> None:
        super().__init__()
        self._instance = instance

    @property
    def sc(self) -> "SparkContext":
        raise RuntimeError("Accessing SparkContext is not supported on Connect")

    def save(self, path: str) -> None:
        from pyspark.ml.wrapper import JavaModel

        if isinstance(self._instance, JavaModel):
            from pyspark.sql.connect.session import SparkSession

            session = SparkSession.getActiveSession()
            assert session is not None
            instance = cast("JavaModel", self._instance)
            params = serialize_ml_params(instance, session.client)

            writer = pb2.MlCommand.Writer(
                model_ref=pb2.ModelRef(id=instance._java_obj),
                params=params,
                path=path,
                should_overwrite=self.shouldOverwrite,
                options=self.optionMap,
            )
            req = session.client._execute_plan_request_with_metadata()
            req.plan.ml_command.write.CopyFrom(writer)
            session.client.execute_ml(req)


class RemoteMLReader(MLReader[RL]):
    def __init__(self, clazz: Type["JavaMLReadable[RL]"]) -> None:
        super().__init__()
        self._clazz = clazz

    def load(self, path: str) -> RL:
        from pyspark.sql.connect.session import SparkSession

        session = SparkSession.getActiveSession()
        assert session is not None

        java_package = (
            self._clazz.__module__.replace("pyspark", "org.apache.spark")
            + "."
            + self._clazz.__name__
        )
        reader = pb2.MlCommand.Reader(clazz=java_package, path=path)
        req = session.client._execute_plan_request_with_metadata()
        req.plan.ml_command.read.CopyFrom(reader)
        model_info = deserialize(session.client.execute_ml(req))
        session.client.add_ml_model(model_info.model_ref.id)
        instance = self._clazz(model_info.model_ref.id)
        instance._resetUid(model_info.uid)
        params = {k: LiteralExpression._to_value(v) for k, v in model_info.params.params.items()}
        instance._set(**params)
        return instance
