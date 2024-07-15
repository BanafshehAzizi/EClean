package eclean.refactor;


import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.epsilon.common.module.ModuleElement;
import org.eclipse.epsilon.eol.EolModule;
import org.eclipse.epsilon.eol.dom.AndOperatorExpression;
import org.eclipse.epsilon.eol.dom.Case;
import org.eclipse.epsilon.eol.dom.ContinueStatement;
import org.eclipse.epsilon.eol.dom.Expression;
import org.eclipse.epsilon.eol.dom.FeatureCallExpression;
import org.eclipse.epsilon.eol.dom.ForStatement;
import org.eclipse.epsilon.eol.dom.IfStatement;
import org.eclipse.epsilon.eol.dom.NameExpression;
import org.eclipse.epsilon.eol.dom.NotOperatorExpression;
import org.eclipse.epsilon.eol.dom.Operation;
import org.eclipse.epsilon.eol.dom.OperationCallExpression;
import org.eclipse.epsilon.eol.dom.ReturnStatement;
import org.eclipse.epsilon.eol.dom.Statement;
import org.eclipse.epsilon.eol.dom.StatementBlock;
import org.eclipse.epsilon.eol.dom.ThrowStatement;
import org.eclipse.epsilon.eol.dom.WhileStatement;
import org.eclipse.epsilon.eol.parse.EolUnparser;
import org.eclipse.epsilon.eol.parse.Eol_EolParserRules.caseStatement_return;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

public class Refactor implements IObjectActionDelegate {
	Refactor refactor;
	
	public Refactor (){
		
	}

	@Override
	public void run(IAction action) {
		new CPUUtils();
		long startTime = CPUUtils.getUserTime();
	
		Boolean dirty = true;
		ArrayList<ModuleElement> operationCallExpressions = new ArrayList<>();
		
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		ISelection selection = window.getSelectionService().getSelection();
		IFile file = (IFile) ((IStructuredSelection) selection).getFirstElement();
		IPath path = file.getLocation();
		String src = path.toPortableString();
		while (dirty) {
			dirty = false;
			
			EolModule eolModule = new EolModule();
			try {
				eolModule.parse(new File(src));
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			ArrayList<String> operationNames = new ArrayList<>();
			for(Operation operation : eolModule.getOperations()) {
				operationNames.add(operation.getName());
			}

			for (ModuleElement element : eolModule.getMain().getStatements()) {
				operationCallExpressions.addAll(hasStatement(element, OperationCallExpression.class, new ArrayList<>()));
			
				for (ModuleElement operationCallExpression : operationCallExpressions) {
					String name = ((FeatureCallExpression)operationCallExpression).getName();
					if(operationNames.contains(name)) {
						operationNames.remove(name);
					}
				}

				ArrayList<ModuleElement> ifStatements = hasStatement(element, IfStatement.class, new ArrayList<>());

				for (ModuleElement ifStatement : ifStatements) {
					dirty = IfStatementInLoop(ifStatement) ? true : dirty;
					IfStatement newIfStatement = CombineIfStatements(ifStatement);
					if (newIfStatement != null) {
						dirty = true;
						((StatementBlock)(ifStatement.getParent())).getStatements().set(0, newIfStatement);
					}
				}
			}

			for (Operation element : eolModule.getOperations()) {
				Integer operationCounter = 0; operationCounter++;
				ArrayList<ModuleElement> returnStatements = hasStatement(element, ReturnStatement.class, new ArrayList<>());
	
				for (ModuleElement	returnStatement : returnStatements) {
					dirty = RemoveDeadCode(returnStatement) ? true : dirty;
				}
				
				ArrayList<ModuleElement> throwStatements = hasStatement(element, ThrowStatement.class, new ArrayList<>());
				for (ModuleElement throwStatement : throwStatements) {
					dirty = RemoveDeadCode(throwStatement) ? true : dirty;
				}
			}

			for (Operation element : eolModule.getOperations()) {
				operationCallExpressions.addAll(hasStatement(element, OperationCallExpression.class, new ArrayList<>()));
			
				for (ModuleElement operationCallExpression : operationCallExpressions) {
					String name = ((FeatureCallExpression)operationCallExpression).getName();
					if(operationNames.contains(name)) {
						operationNames.remove(name); 
					}
				}

				ArrayList<ModuleElement> ifStatements1 = hasStatement(element, IfStatement.class, new	ArrayList<>());
				for (ModuleElement ifStatement : ifStatements1) {
					dirty = RemoveExtraElse((IfStatement)ifStatement) ? true : dirty;
					Boolean dirty1 = IfStatementInLoop(ifStatement) ? true : dirty;
					IfStatement newIfStatement = CombineIfStatements(ifStatement);
					if (newIfStatement != null) {
						dirty = true;
						((StatementBlock)(ifStatement.getParent())).getStatements().set(0, newIfStatement);
					}
					dirty = dirty || dirty1;
				}
			}
		
			if(dirty == false && !operationNames.isEmpty()) {
				List<Operation> toRemove = new ArrayList<Operation>();
				for(Operation operation : eolModule.getOperations()) {
					if(operationNames.contains(operation.getName())) {
						toRemove.add(operation);
					}
				}
				eolModule.getOperations().removeAll(toRemove);
			}

			String unparse = new EolUnparser().unparse(eolModule);
			unparse = unparse.replaceAll("self.default","self.`default`");
			unparse = unparse.replaceAll("var model =","var `model` =");
			unparse = unparse.replaceAll("model.name","`model`.name");
			unparse = unparse.replaceAll("operation.name","`operation`.name");
			unparse = unparse.replaceAll("classRef.model = model","classRef.`model` = `model`");
			unparse = unparse.replaceAll("not not","");

			try {
				Files.write(Paths.get(src), unparse.getBytes());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		long stopTime = CPUUtils.getUserTime();
		System.out.print("Time: " + (stopTime - startTime) /1000000 +" ms");
	}
	
	private static ArrayList<ModuleElement> hasStatement(ModuleElement element, Class type,	ArrayList<ModuleElement> desiredElements) {

			if (element.getChildren().isEmpty()) {
				return desiredElements;
			}
	
			for (ModuleElement ch : element.getChildren()) {
				hasStatement(ch, type, desiredElements);
			}
	
			if (element.getClass() == type) {
				desiredElements.add(element);
				return desiredElements;
			}
			return desiredElements;
	}

	private static List<Statement> getAllStatements (ModuleElement element) {
		List<Statement> allStatements = new ArrayList<Statement>();
		if (!(element instanceof StatementBlock)) {
			allStatements.add((Statement)element);
			return allStatements;
		}

		for (ModuleElement ch : ((StatementBlock)element).getStatements()) {
			allStatements.addAll(getAllStatements(ch));
		}
		return allStatements;
	}

	public static IfStatement CombineIfStatements(ModuleElement element) {
		List<Statement> IfBlockStatements = ((IfStatement) element).getThenStatementBlock().getStatements();
		if (!(IfBlockStatements.size() == 1 && IfBlockStatements.get(0) instanceof IfStatement)) {
			return null;
		}

		IfStatement statement = (IfStatement) IfBlockStatements.get(0);

		Expression currentCondition = ((IfStatement) element).getConditionExpression();
		Expression newCondition = ((IfStatement) statement).getConditionExpression();

		AndOperatorExpression newConditionExpression = new AndOperatorExpression();
		newConditionExpression.setFirstOperand(currentCondition);
		newConditionExpression.setSecondOperand(newCondition);

		((IfStatement) statement).setConditionExpression(newConditionExpression);

		return statement;
	}

	public static boolean IfStatementInLoop(ModuleElement element) {
		if (!((element.getParent().getParent() instanceof WhileStatement || element.getParent().getParent() instanceof ForStatement) && (((StatementBlock)(element.getParent())).getStatements().size() == 1))) {
			return false;
		}


		if(((IfStatement) element).getElseStatementBlock() == null) {

			Expression condition = ((IfStatement) element).getConditionExpression();
			Expression newCondition = new NotOperatorExpression(condition);
			((IfStatement) element).setConditionExpression(newCondition);

			List<Statement> statements = ((IfStatement) element).getThenStatementBlock().getStatements();			
			((StatementBlock)element.getParent()).getStatements().addAll(statements);

			ContinueStatement continuStatement = new ContinueStatement();
			((IfStatement) element).getThenStatementBlock().getStatements().clear();
			((IfStatement) element).getThenStatementBlock().getStatements().add(continuStatement);
			return true;
		}
		if(getAllStatements(((IfStatement) element).getElseStatementBlock()).size() <
				getAllStatements(((IfStatement) element).getThenStatementBlock()).size()) {
			Expression condition = ((IfStatement) element).getConditionExpression();
			Expression newCondition = new NotOperatorExpression(condition);
			((IfStatement) element).setConditionExpression(newCondition);

			List<Statement> statements = ((IfStatement)
					element).getThenStatementBlock().getStatements();
			((StatementBlock)element.getParent()).getStatements().addAll(statements);

			List<Statement> elseStatements = ((IfStatement) element).getElseStatementBlock().getStatements();			

			ContinueStatement continuStatement = new ContinueStatement();
			((IfStatement) element).getThenStatementBlock().getStatements().clear();
			((IfStatement) element).getThenStatementBlock().getStatements().addAll(elseStatements);
			((IfStatement) element).getThenStatementBlock().getStatements().add(continuStatement);
			((IfStatement) element).setElseStatementBlock(null);
			return true;
		}


		List<Statement> elseStatements = ((IfStatement) element).getElseStatementBlock().getStatements();			
		((StatementBlock)element.getParent()).getStatements().addAll(elseStatements);

		ContinueStatement continuStatement = new ContinueStatement();
		((IfStatement) element).getThenStatementBlock().getStatements().add(continuStatement);
		((IfStatement) element).setElseStatementBlock(null);
		return true;
	}

	public static boolean RemoveExtraElse(IfStatement element) {
		if (element.getElseStatementBlock() == null) {
	        return false;
	    }

	    List<Statement> thenStatements = element.getThenStatementBlock().getStatements();
	    Statement lastStatement = thenStatements.get(thenStatements.size() - 1);

	    if (lastStatement instanceof IfStatement) {
	        boolean removedElse = RemoveExtraElse((IfStatement) lastStatement);
	        if (removedElse) {
	            return true;
	        }
	    }

	    if (!(lastStatement instanceof ReturnStatement)) {
	        return false;
	    }

	    List<Statement> elseStatements = element.getElseStatementBlock().getStatements();

	    StatementBlock parentBlock = findParentBlock(element);

	    if (parentBlock != null) {
	        int index = parentBlock.getStatements().indexOf(element);
	        parentBlock.getStatements().addAll(index + 1, elseStatements);

	        element.setElseStatementBlock(null);
	        return true;
	    }
	    return true;
	}
	
	private static StatementBlock findParentBlock(IfStatement element) {
	    ModuleElement parent = element.getParent();
	    while (parent != null) {
	        if (parent instanceof StatementBlock) {
	            return (StatementBlock) parent;
	        } else if (parent instanceof IfStatement) {
	            IfStatement ifParent = (IfStatement) parent;
	            if (ifParent.getElseStatementBlock() != null) {
	                return ifParent.getElseStatementBlock();
	            }
	        }
	        parent = parent.getParent();
	    }
	    return null;
	}

	public static boolean RemoveDeadCode(ModuleElement element) {
		Boolean dirty = false;
		ModuleElement parent = element.getParent();
		if (parent instanceof StatementBlock)
			parent = parent.getParent();

		List <ModuleElement> statements = parent.getChildren();
		Integer index = statements.indexOf(element);
		Integer size = statements.size();

		if (size == index -1) {
			return dirty;
		}

		if (parent instanceof Operation) {
			List<Statement> operationStatements = ((Operation) parent).getBody().getStatements();

			index = operationStatements.indexOf(element);
			for (Integer i = (index+1); i < operationStatements.size(); i++) {
				dirty = true;
				operationStatements.remove(operationStatements.get(i));
			}
		}
		else
			if(parent instanceof IfStatement)
			{
				List<Statement> ifStatements = ((IfStatement) parent).getThenStatementBlock().getStatements();

				if(ifStatements.indexOf(element) == -1){
					if(!((IfStatement) parent).getElseStatementBlock().getStatements().isEmpty())
					ifStatements = ((IfStatement) parent).getElseStatementBlock().getStatements();
				}

				index = ifStatements.indexOf(element);
				for (Integer i = (index+1); i < ifStatements.size(); i++) {
					dirty = true;
					ifStatements.remove(ifStatements.get(i));
				}
			}
			else {
				if (!(parent instanceof ForStatement || parent instanceof WhileStatement)) {
					return dirty;
				}
				List<Statement> loopStatements = null;
				if (parent instanceof ForStatement)
				{
					loopStatements = ((ForStatement)parent).getBodyStatementBlock().getStatements();
				}
				if(parent instanceof WhileStatement) {
					loopStatements = ((WhileStatement)parent).getBodyStatementBlock().getStatements();
				}
				
				index = loopStatements.indexOf(element);
				for (Integer i = (index+1); i < loopStatements.size(); i++) {
					dirty = true;
					loopStatements.remove(loopStatements.get(i));
				}
			}

		return dirty;
	}

	@Override
	public void selectionChanged(IAction action, ISelection selection) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void setActivePart(IAction action, IWorkbenchPart targetPart) {
		// TODO Auto-generated method stub
		
	}
	
}
