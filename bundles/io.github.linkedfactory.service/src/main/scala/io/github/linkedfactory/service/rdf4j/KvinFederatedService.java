package io.github.linkedfactory.service.rdf4j;

import io.github.linkedfactory.kvin.Kvin;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import org.eclipse.rdf4j.common.iteration.*;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.evaluation.federation.FederatedService;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.util.QueryEvaluationUtil;
import scala.reflect.internal.TypeDebugging;
import scala.tools.reflect.Eval;

import java.util.*;

public class KvinFederatedService implements FederatedService {
	static final ValueFactory vf = SimpleValueFactory.getInstance();
	Kvin kvin;
	
	protected boolean initialized = false;

	public KvinFederatedService(Kvin kvin) {
		this.kvin = kvin;
	}

	@Override
	public boolean ask(Service service, BindingSet bindings, String baseUri) throws QueryEvaluationException {
		final CloseableIteration<BindingSet, QueryEvaluationException> iter = evaluate(service,
				new SingletonIteration<>(bindings), baseUri);
		try {
			while (iter.hasNext()) {
				BindingSet bs = iter.next();
				String firstVar = service.getBindingNames().iterator().next();
				return QueryEvaluationUtil.getEffectiveBooleanValue(bs.getValue(firstVar));
			}
		} finally {
			iter.close();
		}
		return false;
	}

	@Override
	public CloseableIteration<BindingSet, QueryEvaluationException> evaluate(Service service,
			CloseableIteration<BindingSet, QueryEvaluationException> bindings, String baseUri)
					throws QueryEvaluationException {
		if (!bindings.hasNext()) {
			return new EmptyIteration<>();
		}

		TupleExpr expr = service.getArg();
		final ParameterScanner scanner = new ParameterScanner();
		try {
			scanner.process(expr);
		} catch (RDF4JException e) {
			throw new QueryEvaluationException(e);
		}

		Map<Value, Object> valueToData = new WeakHashMap<>();
		EvaluationStrategy strategy = new KvinEvaluationStrategy(kvin, scanner, vf, null, null, valueToData);

		List<CloseableIteration<BindingSet, QueryEvaluationException>> resultIters = new ArrayList<>();
		while (bindings.hasNext()) {
			BindingSet bs = bindings.next();
			resultIters.add(strategy.evaluate(expr, bs));
		}

		return resultIters.size() > 1 ? new DistinctIteration<>(new UnionIteration<>(resultIters)) : resultIters.get(0);
	}

	@Override
	public void initialize() throws QueryEvaluationException {
		initialized = true;
	}

	@Override
	public boolean isInitialized() {
		return initialized;
	}

	@Override
	public CloseableIteration<BindingSet, QueryEvaluationException> select(Service service,
			final Set<String> projectionVars, BindingSet bindings, String baseUri) throws QueryEvaluationException {
		final CloseableIteration<BindingSet, QueryEvaluationException> iter = evaluate(service,
				new SingletonIteration<>(bindings), baseUri);
		if (service.getBindingNames().equals(projectionVars)) {
			return iter;
		}

		return new CloseableIteration<>() {
			@Override
			public boolean hasNext() throws QueryEvaluationException {
				return iter.hasNext();
			}

			@Override
			public BindingSet next() throws QueryEvaluationException {
				QueryBindingSet projected = new QueryBindingSet(bindings);
				BindingSet result = iter.next();
				for (String var : projectionVars) {
					Value v = result.getValue(var);
					projected.addBinding(var, v);
				}
				return projected;
			}

			@Override
			public void remove() throws QueryEvaluationException {
				iter.remove();
			}

			@Override
			public void close() throws QueryEvaluationException {
				iter.close();
			}
		};
	}

	@Override
	public void shutdown() throws QueryEvaluationException {
	}
}
